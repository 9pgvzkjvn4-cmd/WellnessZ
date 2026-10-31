package com.wellnessz.e32.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.support.E32Support;

/** Data access for configuration versions ({@code devicecat.configuration}). Optimistic locking on {@code row_version}. */
@Component("e32ConfigurationStore")
public class ConfigurationStore {

    private static final String COLUMNS = """
            select row_id, configuration_id, version, device_instance_id, status, paused, current_step, model_code, option_code, site_code,
                   room_code, station_code, tenant_code, endpoint_id, endpoint_kind, zone, equipment_device_id, serial, udi, firmware,
                   software_version, driver_package_id, driver_version, data, cloned_from, template_id, created_by, created_at, updated_by,
                   updated_at, submitted_by, submitted_at, approved_by, approved_at, co_approved_by, published_by, published_at, status_reason,
                   row_version from devicecat.configuration""";

    private final E32Support support;

    public ConfigurationStore(E32Support support) {
        this.support = support;
    }

    /** Latest version of a configuration. */
    public Optional<Configuration> latest(String configurationId) {
        return support.jdbc().query(COLUMNS + " where configuration_id = ? order by version desc limit 1", this::map, configurationId)
                .stream().findFirst();
    }

    public Configuration requireLatest(String configurationId) {
        return latest(configurationId).orElseThrow(() -> ApiException.notFound("Device configuration"));
    }

    public Optional<Configuration> version(String configurationId, int version) {
        return support.jdbc().query(COLUMNS + " where configuration_id = ? and version = ?", this::map, configurationId, version).stream().findFirst();
    }

    public List<Configuration> versions(String configurationId) {
        return support.jdbc().query(COLUMNS + " where configuration_id = ? order by version", this::map, configurationId);
    }

    /** The live (published / suspended / quarantined) version of a device instance, newest first. */
    public Optional<Configuration> live(String deviceInstanceId) {
        return support.jdbc().query(COLUMNS + " where device_instance_id = ? and status in ('PUBLISHED','SUSPENDED','QUARANTINED') order by version desc limit 1",
                this::map, deviceInstanceId).stream().findFirst();
    }

    public List<Configuration> liveAll() {
        return support.jdbc().query(COLUMNS + " where status in ('PUBLISHED','SUSPENDED','QUARANTINED') order by device_instance_id, version desc", this::map);
    }

    public List<Configuration> liveForEndpoint(String endpointId) {
        return support.jdbc().query(COLUMNS + " where endpoint_id = ? and status in ('PUBLISHED','SUSPENDED','QUARANTINED') order by device_instance_id",
                this::map, endpointId);
    }

    public List<Configuration> liveAtStation(String siteCode, String stationCode) {
        return support.jdbc().query(COLUMNS + " where site_code = ? and station_code = ? and status = 'PUBLISHED' order by device_instance_id",
                this::map, siteCode, stationCode);
    }

    public List<Configuration> liveOnDriver(String packageId, String version) {
        return support.jdbc().query(COLUMNS + " where driver_package_id = ? and driver_version = ? and status in ('PUBLISHED','SUSPENDED','QUARANTINED')",
                this::map, packageId, version);
    }

    /** Latest version of every configuration. */
    public List<Configuration> latestAll() {
        return support.jdbc().query(COLUMNS + " c where version = (select max(version) from devicecat.configuration x"
                + " where x.configuration_id = c.configuration_id) order by configuration_id", this::map);
    }

    public void insert(Configuration c) {
        Instant now = support.now();
        if (c.rowId == null) {
            c.rowId = UUID.randomUUID();
        }
        c.createdAt = c.createdAt == null ? now : c.createdAt;
        c.updatedAt = now;
        c.updatedBy = c.updatedBy == null ? c.createdBy : c.updatedBy;
        support.jdbc().update("""
                insert into devicecat.configuration (row_id, configuration_id, version, device_instance_id, status, paused, current_step, model_code,
                    option_code, site_code, room_code, station_code, tenant_code, endpoint_id, endpoint_kind, zone, equipment_device_id, serial, udi,
                    firmware, software_version, driver_package_id, driver_version, data, cloned_from, template_id, created_by, created_at, updated_by,
                    updated_at, submitted_by, submitted_at, approved_by, approved_at, co_approved_by, published_by, published_at, status_reason, row_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                c.rowId, c.configurationId, c.version, c.deviceInstanceId, c.status, c.paused, c.currentStep, c.modelCode, c.option, c.siteCode,
                c.roomCode, c.stationCode, c.tenantCode, c.endpointId, c.endpointKind, c.zone, c.equipmentDeviceId, c.serial, c.udi, c.firmware,
                c.softwareVersion, c.driverPackageId, c.driverVersion, support.write(c.data), c.clonedFrom, c.templateId, c.createdBy, ts(c.createdAt),
                c.updatedBy, ts(c.updatedAt), c.submittedBy, ts(c.submittedAt), c.approvedBy, ts(c.approvedAt), c.coApprovedBy, c.publishedBy,
                ts(c.publishedAt), c.statusReason);
        c.rowVersion = 0;
    }

    public void update(Configuration c, String actor) {
        c.updatedBy = actor;
        c.updatedAt = support.now();
        int n = support.jdbc().update("""
                update devicecat.configuration set status = ?, paused = ?, current_step = ?, model_code = ?, option_code = ?, site_code = ?, room_code = ?,
                    station_code = ?, tenant_code = ?, endpoint_id = ?, endpoint_kind = ?, zone = ?, equipment_device_id = ?, serial = ?, udi = ?,
                    firmware = ?, software_version = ?, driver_package_id = ?, driver_version = ?, data = ?, template_id = ?, updated_by = ?, updated_at = ?,
                    submitted_by = ?, submitted_at = ?, approved_by = ?, approved_at = ?, co_approved_by = ?, published_by = ?, published_at = ?,
                    status_reason = ?, row_version = row_version + 1
                where row_id = ? and row_version = ?""",
                c.status, c.paused, c.currentStep, c.modelCode, c.option, c.siteCode, c.roomCode, c.stationCode, c.tenantCode, c.endpointId,
                c.endpointKind, c.zone, c.equipmentDeviceId, c.serial, c.udi, c.firmware, c.softwareVersion, c.driverPackageId, c.driverVersion,
                support.write(c.data), c.templateId, c.updatedBy, ts(c.updatedAt), c.submittedBy, ts(c.submittedAt), c.approvedBy, ts(c.approvedAt),
                c.coApprovedBy, c.publishedBy, ts(c.publishedAt), c.statusReason, c.rowId, c.rowVersion);
        if (n == 0) {
            throw ApiException.conflict("concurrent_modification", "The configuration was changed by someone else; reload and try again");
        }
        c.rowVersion++;
    }

    /** New configuration skeleton (not yet stored). */
    public static Configuration draft(String configurationId, int version, String deviceInstanceId, String modelCode, String actor) {
        Configuration c = new Configuration();
        c.configurationId = configurationId;
        c.version = version;
        c.deviceInstanceId = deviceInstanceId;
        c.modelCode = modelCode;
        c.status = Configuration.DRAFT;
        c.currentStep = 1;
        c.tenantCode = "GROUP";
        c.createdBy = actor;
        c.updatedBy = actor;
        return c;
    }

    /** Copies a configuration into a new, unsaved version / configuration. */
    public static Configuration copyOf(Configuration src, String configurationId, int version, String deviceInstanceId, String actor) {
        Configuration c = draft(configurationId, version, deviceInstanceId, src.modelCode, actor);
        c.option = src.option;
        c.siteCode = src.siteCode;
        c.roomCode = src.roomCode;
        c.stationCode = src.stationCode;
        c.tenantCode = src.tenantCode;
        c.endpointId = src.endpointId;
        c.endpointKind = src.endpointKind;
        c.zone = src.zone;
        c.equipmentDeviceId = src.equipmentDeviceId;
        c.serial = src.serial;
        c.udi = src.udi;
        c.firmware = src.firmware;
        c.softwareVersion = src.softwareVersion;
        c.driverPackageId = src.driverPackageId;
        c.driverVersion = src.driverVersion;
        c.templateId = src.templateId;
        c.data = com.wellnessz.e32.support.Docs.copyMap(src.data);
        return c;
    }

    public static void markCloned(Configuration c, String from) {
        c.clonedFrom = from;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private Configuration map(ResultSet rs, int i) throws SQLException {
        Configuration c = new Configuration();
        c.rowId = rs.getObject("row_id", UUID.class);
        c.configurationId = rs.getString("configuration_id");
        c.version = rs.getInt("version");
        c.deviceInstanceId = rs.getString("device_instance_id");
        c.status = rs.getString("status");
        c.paused = rs.getBoolean("paused");
        c.currentStep = rs.getInt("current_step");
        c.modelCode = rs.getString("model_code");
        c.option = rs.getString("option_code");
        c.siteCode = rs.getString("site_code");
        c.roomCode = rs.getString("room_code");
        c.stationCode = rs.getString("station_code");
        c.tenantCode = rs.getString("tenant_code");
        c.endpointId = rs.getString("endpoint_id");
        c.endpointKind = rs.getString("endpoint_kind");
        c.zone = rs.getString("zone");
        c.equipmentDeviceId = rs.getString("equipment_device_id");
        c.serial = rs.getString("serial");
        c.udi = rs.getString("udi");
        c.firmware = rs.getString("firmware");
        c.softwareVersion = rs.getString("software_version");
        c.driverPackageId = rs.getString("driver_package_id");
        c.driverVersion = rs.getString("driver_version");
        c.data = support.readMap(rs.getString("data"));
        c.clonedFrom = rs.getString("cloned_from");
        c.templateId = rs.getObject("template_id", UUID.class);
        c.createdBy = rs.getString("created_by");
        c.createdAt = inst(rs, "created_at");
        c.updatedBy = rs.getString("updated_by");
        c.updatedAt = inst(rs, "updated_at");
        c.submittedBy = rs.getString("submitted_by");
        c.submittedAt = inst(rs, "submitted_at");
        c.approvedBy = rs.getString("approved_by");
        c.approvedAt = inst(rs, "approved_at");
        c.coApprovedBy = rs.getString("co_approved_by");
        c.publishedBy = rs.getString("published_by");
        c.publishedAt = inst(rs, "published_at");
        c.statusReason = rs.getString("status_reason");
        c.rowVersion = rs.getLong("row_version");
        return c;
    }
}
