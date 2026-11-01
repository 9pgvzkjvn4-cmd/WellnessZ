package com.wellnessz.casekernel.device;

import java.util.Optional;

/**
 * Tenants of the device platform (E31-HLR-12), implemented by the engine (E31 {@code /device-engine/tenants}) and read by the
 * console (E32 – catalogue visibility, P6 residency check) and security (E34 – per-tenant signing keys). Contract section 12.
 */
public interface DeviceTenantDirectory {

    /** The airline group: the default tenant of every row. */
    String GROUP = "GROUP";

    Optional<Tenant> tenant(String code);

    /**
     * @param catalogueScope  {@code GROUP} – the shared group catalogue plus the tenant's own models and packages;
     *                        {@code TENANT} – only the tenant's own models and packages
     * @param residencyRegion data-residency region (P6 vendor clouds must match it – {@code residency_check})
     */
    record Tenant(String code, String catalogueScope, String residencyRegion, int messagesPerMinute, int maxConnectors) {

        /** Whether a catalogue row owned by {@code ownerTenant} ({@code null} = shared group catalogue) is visible. */
        public boolean sees(String ownerTenant) {
            if (ownerTenant == null || ownerTenant.isBlank()) {
                return !"TENANT".equals(catalogueScope);
            }
            return ownerTenant.equals(code);
        }
    }

    /** Default when E31 is not deployed: only the group tenant, shared catalogue, residency unknown. */
    DeviceTenantDirectory GROUP_ONLY = code -> GROUP.equals(code == null ? GROUP : code)
            ? Optional.of(new Tenant(GROUP, GROUP, null, 6000, 1000)) : Optional.empty();

    /** Normalises a missing tenant code to {@link #GROUP}. */
    static String normalise(String code) {
        return code == null || code.isBlank() ? GROUP : code;
    }
}
