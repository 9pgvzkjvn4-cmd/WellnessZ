package io.intimed.tenancy;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.common.Paths2;
import io.intimed.security.auth.Role;
import io.intimed.security.auth.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

/**
 * First-start provisioning. LOCAL profile: one tenant and a tenant/platform administrator with a RANDOM initial
 * password written once to {@code <home>/initial-admin.txt} (owner-only) – no default credentials. DEMO profile:
 * synthetic demo tenants and persona users with the documented demo password (demo data only, never production).
 */
@Component
@Order(1)
public class Bootstrap implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);
    public static final String DEMO_PASSWORD = "IntiMed-Demo-2026!";
    private final TenantService tenants;
    private final UserService users;
    private final boolean demo;

    public Bootstrap(TenantService tenants, UserService users, @Value("${intimed.demo.enabled:false}") boolean demo) {
        this.tenants = tenants;
        this.users = users;
        this.demo = demo;
    }

    public record Persona(String tenant, String username, String name, EnumSet<Role> roles) { }

    public static final List<Persona> PERSONAS = List.of(
            new Persona("acme-health", "admin.device", "Dana Okafor (Device Administrator)", EnumSet.of(Role.DEVICE_ADMIN)),
            new Persona("acme-health", "approver.device", "Ari Lindqvist (Device Administrator – approver)", EnumSet.of(Role.DEVICE_ADMIN)),
            new Persona("acme-health", "template.owner", "Mei Tan (Template Owner, clinical informatics)", EnumSet.of(Role.TEMPLATE_OWNER)),
            new Persona("acme-health", "nurse.icu", "Sam Rivera (ICU nurse)", EnumSet.of(Role.CLINICIAN)),
            new Persona("acme-health", "nurse.clinic", "Priya Nair (Clinic nurse)", EnumSet.of(Role.CLINICIAN)),
            new Persona("acme-health", "physician", "Dr Jonas Weber (Physician)", EnumSet.of(Role.PHYSICIAN)),
            new Persona("acme-health", "soc.analyst", "Lena Novak (Security Analyst / SOC)", EnumSet.of(Role.SECURITY_ANALYST)),
            new Persona("acme-health", "ciso", "Omar Haddad (CISO / DPO)", EnumSet.of(Role.CISO_DPO)),
            new Persona("acme-health", "sre", "Kai Moreno (Platform SRE)", EnumSet.of(Role.PLATFORM_SRE)),
            new Persona("acme-health", "tenant.admin", "Grace Mensah (Tenant Administrator)", EnumSet.of(Role.TENANT_ADMIN)),
            new Persona("acme-health", "dev.integrator", "Tom Becker (Integration Developer)", EnumSet.of(Role.INTEGRATION_DEV)),
            new Persona("acme-health", "auditor", "Ines Duarte (Auditor, read-only)", EnumSet.of(Role.AUDITOR)),
            new Persona("acme-health", "regulatory", "Hugo Laurent (Regulatory & Quality)", EnumSet.of(Role.REGULATORY)),
            new Persona("ohcm-airline", "oh.admin", "Rania Aziz (OHCM Device Administrator)", EnumSet.of(Role.DEVICE_ADMIN)),
            new Persona("ohcm-airline", "oh.nurse", "Chris Doyle (OH nurse)", EnumSet.of(Role.CLINICIAN)));

    @Override
    public void run(ApplicationArguments args) {
        if (!tenants.all().isEmpty()) return;
        if (demo) {
            tenants.create("acme-health", "Acme Health Group (demo hospital & clinics)", "eu-west", "ENTERPRISE", null, "system:bootstrap");
            var oh = TenantService.defaults();
            oh.put("jurisdiction", "UAE");
            tenants.create("ohcm-airline", "OHCM – Airline occupational health (reference client)", "me-central", "ENTERPRISE", oh, "system:bootstrap");
            for (Persona p : PERSONAS) users.create(p.tenant(), p.username(), DEMO_PASSWORD, p.name(), p.roles(), "system:bootstrap");
            LOG.info("DEMO profile: {} persona users created (password documented in the demo guide – synthetic data only)", PERSONAS.size());
            return;
        }
        tenants.create("local", "Local development tenant", "client-hosted", "DEVELOPER", null, "system:bootstrap");
        String pw = "Im-" + Hashing.randomToken(12) + "9a";
        UserService.User admin = users.create("local", "admin", pw, "Local administrator", EnumSet.of(Role.TENANT_ADMIN, Role.DEVICE_ADMIN, Role.SECURITY_ANALYST, Role.PLATFORM_SRE), "system:bootstrap");
        String totp = users.totpSecret(admin);
        users.create("local", "approver", "Im-" + Hashing.randomToken(12) + "7b", "Second approver (reset before use)", EnumSet.of(Role.DEVICE_ADMIN), "system:bootstrap");
        Paths2.writeSecret(Paths2.home().resolve("initial-admin.txt"), ("username: admin\npassword: " + pw + "\nMFA (TOTP) secret: " + totp + "\notpauth://totp/IntiMed:admin?secret=" + totp + "&issuer=IntiMed\nAdd it to an authenticator app, then delete this file.\n").getBytes(StandardCharsets.UTF_8));
        LOG.warn("LOCAL profile: initial administrator credentials written to {} (owner-only). Delete after first sign-in.", Paths2.home().resolve("initial-admin.txt"));
    }
}
