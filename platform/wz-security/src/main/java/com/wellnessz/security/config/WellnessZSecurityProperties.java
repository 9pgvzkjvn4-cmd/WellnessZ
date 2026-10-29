package com.wellnessz.security.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity configuration. Several trusted issuers may be configured at once, so the same deployment can accept
 * tokens from Microsoft Entra ID (workforce), Keycloak (partners / local) or another OIDC provider.
 */
@ConfigurationProperties(prefix = "wellnessz.security")
public class WellnessZSecurityProperties {

    /** Trusted OIDC issuer URIs (exact match against the {@code iss} claim). */
    private List<String> issuers = new ArrayList<>();

    /** Required audience ({@code aud}) – the API identifier registered at the IdP. */
    private String audience = "wellnessz-api";

    /** Claim paths that carry roles. Keycloak, Entra ID app roles and groups are covered by default. */
    private List<String> roleClaims = new ArrayList<>(List.of("realm_access.roles", "roles", "groups"));

    /** Optional mapping of IdP group object-ids / names to application roles (Entra ID security groups). */
    private Map<String, String> groupRoleMapping = new LinkedHashMap<>();

    /** Claim holding the HR employee number used to link the identity to the employee master record. */
    private String employeeIdClaim = "employee_id";

    /** Claim holding the org-unit path the principal manages / belongs to, e.g. {@code /AIRLINE/OPS/CABIN}. */
    private String orgScopeClaim = "org_scope";

    /** Allowed CORS origins (only needed for native/mobile clients calling the API directly). */
    private List<String> corsOrigins = new ArrayList<>();

    public List<String> getIssuers() {
        return issuers;
    }

    public void setIssuers(List<String> issuers) {
        this.issuers = issuers;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public List<String> getRoleClaims() {
        return roleClaims;
    }

    public void setRoleClaims(List<String> roleClaims) {
        this.roleClaims = roleClaims;
    }

    public Map<String, String> getGroupRoleMapping() {
        return groupRoleMapping;
    }

    public void setGroupRoleMapping(Map<String, String> groupRoleMapping) {
        this.groupRoleMapping = groupRoleMapping;
    }

    public String getEmployeeIdClaim() {
        return employeeIdClaim;
    }

    public void setEmployeeIdClaim(String employeeIdClaim) {
        this.employeeIdClaim = employeeIdClaim;
    }

    public String getOrgScopeClaim() {
        return orgScopeClaim;
    }

    public void setOrgScopeClaim(String orgScopeClaim) {
        this.orgScopeClaim = orgScopeClaim;
    }

    public List<String> getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(List<String> corsOrigins) {
        this.corsOrigins = corsOrigins;
    }
}
