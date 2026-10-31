package com.wellnessz.e34.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DeviceSecretStore;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.identity.EndpointRepository;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.secrets.FieldEncryptor;
import com.wellnessz.security.audit.AuditService;

/**
 * Connector secrets (E34-HLR-05, E34-F04; contract §15.6) on wz-secrets field encryption (context {@code devsec:<ref>}). A value
 * is accepted once and never returned to a browser: the API exposes metadata only. {@link #resolve} serves the engine runtime
 * (no person in the security context) and gateway endpoints bound to the secret's instances; every resolution is audited with
 * its purpose. {@code vault:<path>} references are read from the mount of an external vault agent
 * ({@code wellnessz.device.security.vault-mount}) when configured.
 */
@Service
public class SecretStoreService implements DeviceSecretStore {

    static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9._:\\-]{1,80}");
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:\\- ]{1,120}");
    static final Pattern REF = Pattern.compile("devsec:[0-9a-f\\-]{36}");
    static final Pattern VAULT_REF = Pattern.compile("vault:[A-Za-z0-9._\\-]{1,64}(/[A-Za-z0-9._\\-]{1,64}){0,5}");
    static final Pattern PURPOSE = Pattern.compile("[A-Z][A-Z0-9_]{2,39}");
    static final Pattern INSTANCE = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");
    static final int MAX_VALUE = 8192;

    public record SecretMetadata(String reference, String scope, String name, Instant createdAt, String createdBy, Instant rotatedAt,
                                 Instant lastResolvedAt, List<String> boundInstances, boolean destroyed) {
    }

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final SecurityEventService events;
    private final AuditService audit;
    private final E34Settings settings;
    private final String vaultMount;

    public SecretStoreService(JdbcTemplate jdbc, FieldEncryptor encryptor, SecurityEventService events, AuditService audit, E34Settings settings,
                              @Value("${wellnessz.device.security.vault-mount:}") String vaultMount) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.events = events;
        this.audit = audit;
        this.settings = settings;
        this.vaultMount = vaultMount == null ? "" : vaultMount.strip();
    }

    // ------------------------------------------------------------------ SPI

    @Override
    @Transactional
    public String store(String actor, String scope, String name, char[] value) {
        return store(actor, scope, name, value, List.of()).reference();
    }

    @Transactional
    public SecretMetadata store(String actor, String scope, String name, char[] value, List<String> boundInstances) {
        settings.requireEnabled();
        if (scope == null || !SCOPE.matcher(scope).matches() || name == null || !NAME.matcher(name).matches()) {
            throw ApiException.badRequest("validation_failed", "Scope and name are required");
        }
        requireValue(value);
        List<String> bound = boundInstances == null ? List.of() : boundInstances;
        if (bound.size() > 50 || bound.stream().anyMatch(i -> i == null || !INSTANCE.matcher(i).matches())) {
            throw ApiException.badRequest("validation_failed", "Invalid bound instances");
        }
        String ref = "devsec:" + UUID.randomUUID();
        Instant now = settings.now();
        jdbc.update("insert into devicesec.secret (reference, scope, name, ciphertext, bound_instances, created_by, created_at) values (?,?,?,?,?,?,?)",
                ref, scope, name, encryptor.encrypt(new String(value), "devsec:" + ref), bound.isEmpty() ? null : String.join(",", bound), actor,
                EndpointRepository.ts(now));
        audit.recordAs(actor, "", "DEVICE_SECRET_STORE", "DEVICE_SECRET", ref, AuditService.Outcome.SUCCESS, scope);
        events.record("SECRET_STORED", DeviceCodes.Severity.INFO, null, null, actor, null, Map.of("reference", ref, "scope", scope));
        return metadata(ref).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<char[]> resolve(String reference, String purpose) {
        if (reference == null || purpose == null || !PURPOSE.matcher(purpose).matches() || !settings.enabled()) {
            return Optional.empty();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Optional<DeviceEndpointPrincipal> device = DeviceEndpointPrincipal.current();
        boolean person = auth != null && auth.isAuthenticated() && device.isEmpty() && !"anonymousUser".equals(auth.getPrincipal());
        String actor = device.map(DeviceEndpointPrincipal::authenticationName).orElse(person ? auth.getName() : "system:engine");
        if (person) {
            denied(reference, purpose, actor, "person_caller");
            return Optional.empty(); // secret values are never returned to people / browsers
        }
        if (VAULT_REF.matcher(reference).matches()) {
            return fromVault(reference, purpose, actor);
        }
        if (!REF.matcher(reference).matches()) {
            return Optional.empty();
        }
        List<String[]> rows = jdbc.query("select ciphertext, bound_instances from devicesec.secret where reference = ? and destroyed_at is null",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, reference);
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return Optional.empty();
        }
        if (device.isPresent()) {
            Set<String> bound = rows.get(0)[1] == null ? Set.of() : Set.of(rows.get(0)[1].split(","));
            if (bound.isEmpty() || device.get().deviceInstanceIds().stream().noneMatch(bound::contains)) {
                denied(reference, purpose, actor, "not_bound_to_endpoint");
                return Optional.empty();
            }
        }
        char[] value = encryptor.decrypt(rows.get(0)[0], "devsec:" + reference).toCharArray();
        jdbc.update("update devicesec.secret set last_resolved_at = ? where reference = ?", EndpointRepository.ts(settings.now()), reference);
        audit.recordAs(actor, "", "DEVICE_SECRET_RESOLVE", "DEVICE_SECRET", reference, AuditService.Outcome.SUCCESS, purpose);
        events.record("SECRET_RESOLVED", DeviceCodes.Severity.INFO, device.map(DeviceEndpointPrincipal::endpointId).orElse(null), null, actor, null,
                Map.of("reference", reference, "purpose", purpose));
        return Optional.of(value);
    }

    @Override
    @Transactional
    public void destroy(String actor, String reference) {
        settings.requireEnabled();
        if (jdbc.update("update devicesec.secret set ciphertext = null, destroyed_by = ?, destroyed_at = ? where reference = ? and destroyed_at is null",
                actor, EndpointRepository.ts(settings.now()), reference) == 0) {
            throw ApiException.notFound("Secret");
        }
        audit.recordAs(actor, "", "DEVICE_SECRET_DESTROY", "DEVICE_SECRET", reference, AuditService.Outcome.SUCCESS, "destroyed");
        events.record("SECRET_DESTROYED", DeviceCodes.Severity.LOW, null, null, actor, null, Map.of("reference", reference));
    }

    @Override
    @Transactional
    public void rotate(String actor, String reference, char[] newValue) {
        settings.requireEnabled();
        requireValue(newValue);
        if (reference == null || !REF.matcher(reference).matches()) {
            throw ApiException.notFound("Secret");
        }
        if (jdbc.update("update devicesec.secret set ciphertext = ?, rotated_by = ?, rotated_at = ? where reference = ? and destroyed_at is null",
                encryptor.encrypt(new String(newValue), "devsec:" + reference), actor, EndpointRepository.ts(settings.now()), reference) == 0) {
            throw ApiException.notFound("Secret");
        }
        audit.recordAs(actor, "", "DEVICE_SECRET_ROTATE", "DEVICE_SECRET", reference, AuditService.Outcome.SUCCESS, "rotated");
        events.record("SECRET_ROTATED", DeviceCodes.Severity.INFO, null, null, actor, null, Map.of("reference", reference));
    }

    // ------------------------------------------------------------------ metadata (API)

    @Transactional
    public List<SecretMetadata> list(String actor, String scope) {
        settings.requireEnabled();
        List<SecretMetadata> out = jdbc.query("select reference, scope, name, created_at, created_by, rotated_at, last_resolved_at, bound_instances, "
                + "destroyed_at from devicesec.secret where (?::varchar is null or scope = ?) order by created_at desc limit 500", this::map, scope, scope);
        events.record("SECRET_METADATA_VIEWED", DeviceCodes.Severity.INFO, null, null, actor, null,
                Map.of("scope", scope == null ? "*" : scope, "count", String.valueOf(out.size())));
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<SecretMetadata> metadata(String reference) {
        return jdbc.query("select reference, scope, name, created_at, created_by, rotated_at, last_resolved_at, bound_instances, destroyed_at "
                + "from devicesec.secret where reference = ?", this::map, reference).stream().findFirst();
    }

    private SecretMetadata map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        String bound = rs.getString("bound_instances");
        return new SecretMetadata(rs.getString("reference"), rs.getString("scope"), rs.getString("name"), EndpointRepository.inst(rs, "created_at"),
                rs.getString("created_by"), EndpointRepository.inst(rs, "rotated_at"), EndpointRepository.inst(rs, "last_resolved_at"),
                bound == null ? List.of() : Arrays.asList(bound.split(",")), rs.getTimestamp("destroyed_at") != null);
    }

    private Optional<char[]> fromVault(String reference, String purpose, String actor) {
        if (vaultMount.isEmpty()) {
            return Optional.empty();
        }
        Path root = Path.of(vaultMount).toAbsolutePath().normalize();
        Path file = root.resolve(reference.substring("vault:".length())).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            char[] v = Files.readString(file, StandardCharsets.UTF_8).strip().toCharArray();
            audit.recordAs(actor, "", "DEVICE_SECRET_RESOLVE", "DEVICE_SECRET", reference, AuditService.Outcome.SUCCESS, purpose);
            events.record("SECRET_RESOLVED", DeviceCodes.Severity.INFO, null, null, actor, null, Map.of("reference", reference, "purpose", purpose));
            return Optional.of(v);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void denied(String reference, String purpose, String actor, String reason) {
        audit.recordAs(actor, "", "DEVICE_SECRET_RESOLVE", "DEVICE_SECRET", reference, AuditService.Outcome.DENIED, purpose + " " + reason);
        events.record("SECRET_RESOLVED", DeviceCodes.Severity.MEDIUM, null, null, actor, reason, Map.of("reference", reference, "purpose", purpose,
                "outcome", "DENIED"));
    }

    private static void requireValue(char[] value) {
        if (value == null || value.length == 0 || value.length > MAX_VALUE) {
            throw ApiException.badRequest("validation_failed", "A secret value of at most 8192 characters is required");
        }
    }
}
