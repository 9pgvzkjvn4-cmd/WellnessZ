package com.wellnessz.e32.catalogue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes.Family;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;

/** Data access for catalogue models ({@code devicecat.model}). */
@Component("e32ModelStore")
public class ModelStore {

    private final E32Support support;

    public ModelStore(E32Support support) {
        this.support = support;
    }

    /** A catalogue model row; {@code detail} carries variants, units, forms, option notes, vendor software, UDI, regulatory, security. */
    public record ModelRow(String modelCode, String inventoryId, Family family, String familyLabel, String manufacturer, String model, String ohUse,
                           String connectivity, String protocol, String fdaCodes, String sprint, String confidence, List<String> options,
                           Map<String, Object> detail, String lifecycle, String posture, boolean locallyModified, Set<String> modifiedFields) {

        public ModelRow {
            options = List.copyOf(options);
            modifiedFields = Set.copyOf(modifiedFields);
        }

        public boolean supports(String option) {
            return options.contains(option);
        }

        public boolean highConfidence() {
            return InventoryMapping.highConfidence(confidence);
        }
    }

    private static final String COLUMNS = """
            select model_code, inventory_id, family, family_label, manufacturer, model, oh_use, connectivity, protocol, fda_codes, sprint,
                   confidence, options, detail, lifecycle, posture, locally_modified, modified_fields from devicecat.model""";

    public List<ModelRow> all() {
        return support.jdbc().query(COLUMNS + " order by family, model_code", this::map);
    }

    public Optional<ModelRow> find(String modelCode) {
        if (modelCode == null) {
            return Optional.empty();
        }
        return support.jdbc().query(COLUMNS + " where model_code = ?", this::map, modelCode).stream().findFirst();
    }

    public ModelRow require(String modelCode) {
        return find(modelCode).orElseThrow(() -> ApiException.notFound("Device model"));
    }

    public void updateDetail(ModelRow row, Map<String, Object> detail, Set<String> newlyModified, String actor) {
        Set<String> modified = new LinkedHashSet<>(row.modifiedFields());
        modified.addAll(newlyModified);
        support.jdbc().update("""
                update devicecat.model set detail = ?, locally_modified = ?, modified_fields = ?, updated_by = ?, updated_at = ?,
                    row_version = row_version + 1 where model_code = ?""",
                support.write(detail), !modified.isEmpty(), Docs.joinCsv(modified), actor, Timestamp.from(support.now()), row.modelCode());
    }

    public void updateColumns(ModelRow row, String manufacturer, String model, String ohUse, String connectivity, String protocol, String fdaCodes,
                              List<String> options, Set<String> newlyModified, String actor) {
        Set<String> modified = new LinkedHashSet<>(row.modifiedFields());
        modified.addAll(newlyModified);
        support.jdbc().update("""
                update devicecat.model set manufacturer = ?, model = ?, oh_use = ?, connectivity = ?, protocol = ?, fda_codes = ?, options = ?,
                    locally_modified = ?, modified_fields = ?, updated_by = ?, updated_at = ?, row_version = row_version + 1 where model_code = ?""",
                manufacturer, model, ohUse, connectivity, protocol, fdaCodes, Docs.joinCsv(options), !modified.isEmpty(), Docs.joinCsv(modified), actor,
                Timestamp.from(support.now()), row.modelCode());
    }

    public void setLifecycle(String modelCode, String lifecycle, String actor) {
        support.jdbc().update("update devicecat.model set lifecycle = ?, updated_by = ?, updated_at = ?, row_version = row_version + 1 where model_code = ?",
                lifecycle, actor, Timestamp.from(support.now()), modelCode);
    }

    public void setPosture(String modelCode, String posture, String actor) {
        support.jdbc().update("update devicecat.model set posture = ?, updated_by = ?, updated_at = ?, row_version = row_version + 1 where model_code = ?",
                posture, actor, Timestamp.from(support.now()), modelCode);
    }

    /** Tenant owning a model ({@code null} = shared group catalogue, E31-HLR-12). */
    public String tenantOf(String modelCode) {
        return support.jdbc().query("select tenant_code from devicecat.model where model_code = ?", rs -> rs.next() ? rs.getString(1) : null, modelCode);
    }

    /** Model code → owning tenant, for tenant-scoped models only. */
    public Map<String, String> tenantScoped() {
        Map<String, String> out = new java.util.HashMap<>();
        support.jdbc().query("select model_code, tenant_code from devicecat.model where tenant_code is not null",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString(1), rs.getString(2)));
        return out;
    }

    public void setTenant(String modelCode, String tenantCode, String actor) {
        support.jdbc().update("update devicecat.model set tenant_code = ?, updated_by = ?, updated_at = ?, row_version = row_version + 1 where model_code = ?",
                tenantCode, actor, java.sql.Timestamp.from(support.now()), modelCode);
    }

    private ModelRow map(ResultSet rs, int i) throws SQLException {
        return new ModelRow(rs.getString("model_code"), rs.getString("inventory_id"), Family.valueOf(rs.getString("family")),
                rs.getString("family_label"), rs.getString("manufacturer"), rs.getString("model"), rs.getString("oh_use"), rs.getString("connectivity"),
                rs.getString("protocol"), rs.getString("fda_codes"), rs.getString("sprint"), rs.getString("confidence"),
                Docs.csv(rs.getString("options")), support.readMap(rs.getString("detail")), rs.getString("lifecycle"), rs.getString("posture"),
                rs.getBoolean("locally_modified"), new LinkedHashSet<>(Docs.csv(rs.getString("modified_fields"))));
    }
}
