package com.wellnessz.e34.zoning;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.identity.EndpointPolicy;
import com.wellnessz.e34.identity.EndpointRepository;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.security.audit.AuditService;

/**
 * Network zoning (E34-HLR-02, E34-F02; contract §15.4): zones, conduits and their export as firewall policy templates, and
 * the per-site address ranges of each zone used by the authenticator (gateways and Device Host agents must call from their
 * site's {@code GATEWAY_ZONE}). No range configured means no gateway can authenticate – fail closed.
 */
@Service
public class ZoneService {

    public record Zone(String code, String name, String description, String defaultPolicy) {
    }

    public record Conduit(String id, String fromZone, String toZone, String protocol, String ports, String direction, String description) {
    }

    public record ZoneCidr(UUID id, String siteCode, String zone, String cidr, String createdBy) {
    }

    private static final Set<String> ZONES = Set.of("DEVICE_VLAN", "GATEWAY_ZONE", "CLINIC_LAN", "VENDOR_CLOUD", "CORE");

    private final JdbcTemplate jdbc;
    private final SecurityEventService events;
    private final AuditService audit;
    private final E34Settings settings;
    private final Clock clock;
    private volatile Map<String, List<String>> cidrCache = Map.of();
    private volatile long cidrLoadedAt;

    public ZoneService(JdbcTemplate jdbc, SecurityEventService events, AuditService audit, E34Settings settings, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Zone> zones() {
        settings.requireEnabled();
        return jdbc.query("select code, name, description, default_policy from devicesec.zone order by code",
                (rs, i) -> new Zone(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    @Transactional(readOnly = true)
    public List<Conduit> conduits() {
        settings.requireEnabled();
        return jdbc.query("select id, from_zone, to_zone, protocol, ports, direction, description from devicesec.conduit order by id",
                (rs, i) -> new Conduit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7)));
    }

    /** Firewall policy template – every conduit is an allow rule, everything else is denied (default deny of every zone). */
    public String exportCsv(String actor) {
        List<Conduit> conduits = conduits();
        StringBuilder sb = new StringBuilder("rule,fromZone,toZone,protocol,ports,direction,action,description\n");
        for (Conduit c : conduits) {
            sb.append(csv(c.id())).append(',').append(csv(c.fromZone())).append(',').append(csv(c.toZone())).append(',').append(csv(c.protocol()))
                    .append(',').append(csv(c.ports())).append(',').append(csv(c.direction())).append(",ALLOW,").append(csv(c.description()))
                    .append('\n');
        }
        sb.append("DEFAULT,*,*,*,*,*,DENY,Deny by default between and out of all zones (no device-to-internet path)\n");
        exported(actor, "csv", conduits.size());
        return sb.toString();
    }

    public List<Conduit> exportJson(String actor) {
        List<Conduit> conduits = conduits();
        exported(actor, "json", conduits.size());
        return conduits;
    }

    private void exported(String actor, String format, int count) {
        audit.record("DEVICE_CONDUIT_EXPORT", "DEVICE_ZONE", "conduits", AuditService.Outcome.SUCCESS, format + " rules=" + count);
        events.record("CONFIG_EXPORTED", DeviceCodes.Severity.INFO, null, null, actor, "conduits_export",
                Map.of("format", format, "count", String.valueOf(count)));
    }

    @Transactional(readOnly = true)
    public List<ZoneCidr> cidrs() {
        settings.requireEnabled();
        return jdbc.query("select id, site_code, zone, cidr, created_by from devicesec.zone_cidr order by site_code, zone, cidr",
                (rs, i) -> new ZoneCidr(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    @Transactional
    public ZoneCidr addCidr(String actor, String siteCode, String zone, String cidr) {
        settings.requireEnabled();
        if (!("*".equals(siteCode) || EndpointPolicy.validSite(siteCode)) || !ZONES.contains(zone) || !EndpointPolicy.validCidr(cidr)) {
            throw ApiException.badRequest("validation_failed", "Site code, zone and CIDR range are required");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into devicesec.zone_cidr (id, site_code, zone, cidr, created_by, created_at) values (?,?,?,?,?,?)", id, siteCode, zone,
                    cidr.strip(), actor, EndpointRepository.ts(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("cidr_exists", "The range is already configured");
        }
        cidrCache = Map.of();
        audit.record("DEVICE_ZONE_CIDR_ADD", "DEVICE_ZONE", zone, AuditService.Outcome.SUCCESS, siteCode + " " + cidr.strip());
        return new ZoneCidr(id, siteCode, zone, cidr.strip(), actor);
    }

    @Transactional
    public void removeCidr(String actor, UUID id) {
        settings.requireEnabled();
        if (jdbc.update("delete from devicesec.zone_cidr where id = ?", id) == 0) {
            throw ApiException.notFound("Range");
        }
        cidrCache = Map.of();
        audit.record("DEVICE_ZONE_CIDR_REMOVE", "DEVICE_ZONE", id.toString(), AuditService.Outcome.SUCCESS, "removed");
    }

    /** Address ranges of a zone at a site (site-specific plus the {@code '*'} defaults); cached for 30 seconds. */
    public List<String> ranges(String siteCode, String zone) {
        long now = clock.millis();
        Map<String, List<String>> cache = cidrCache;
        if (cache.isEmpty() || now - cidrLoadedAt > 30_000) {
            cache = jdbc.query("select site_code, zone, cidr from devicesec.zone_cidr", (rs, i) -> new String[] {rs.getString(1) + "|" + rs.getString(2),
                    rs.getString(3)}).stream().collect(Collectors.groupingBy(a -> a[0], Collectors.mapping(a -> a[1], Collectors.toList())));
            cidrCache = Map.copyOf(cache);
            cidrLoadedAt = now;
        }
        List<String> site = cache.getOrDefault(siteCode + "|" + zone, List.of());
        List<String> all = cache.getOrDefault("*|" + zone, List.of());
        if (site.isEmpty()) {
            return all;
        }
        if (all.isEmpty()) {
            return site;
        }
        java.util.ArrayList<String> merged = new java.util.ArrayList<>(site);
        merged.addAll(all);
        return merged;
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s; // CSV formula injection guard
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }
}
