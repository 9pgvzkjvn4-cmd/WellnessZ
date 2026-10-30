# WellnessZ OHCM – Developer Guide: Adding Modules Incrementally

**Version 1.0**

*WellnessZ OHCM platform – increments 1–5*

| | |
|---|---|
| **Document** | 03 – Developer Guide |
| **Audience** | Developers and technical leads building business modules E01–E14 and integrations |
| **Baseline** | Repository working tree, Maven version `0.1.0-SNAPSHOT`, Java 21, Spring Boot 4.1.2; updated for increments 1–5 (E01, E02 – Section 14; E03, E05 – Section 15; E06, E07, E14 – Section 16; E04, E08 and the partner portal – Section 17; E09, E10, E11, E12, restricted classes and the duty-assignment feed – Section 18) |
| **Increment 2 documents** | [Solution Design E03/E05](../increment-02/01-Solution-Design-E03-E05.md) · [Requirements Traceability Matrix](../increment-02/02-Requirements-Traceability-Matrix.md) · [Release Notes](../increment-02/03-Release-Notes-Increment-2.md) · [API Contract](../increment-02/API-Contract-E03-E05.md) |
| **Increment 3 documents** | [Solution Design E06/E07/E14](../increment-03/01-Solution-Design-E06-E07-E14.md) · [Requirements Traceability Matrix](../increment-03/02-Requirements-Traceability-Matrix.md) · [Release Notes](../increment-03/03-Release-Notes-Increment-3.md) · [API Contract](../increment-03/API-Contract-E06-E07-E14.md) |
| **Increment 4 documents** | [Solution Design E04/E08](../increment-04/01-Solution-Design-E04-E08.md) · [Requirements Traceability Matrix](../increment-04/02-Requirements-Traceability-Matrix.md) · [Release Notes](../increment-04/03-Release-Notes-Increment-4.md) · [API Contract](../increment-04/API-Contract-E04-E08.md) |
| **Increment 5 documents** | [Solution Design E09/E10/E11/E12](../increment-05/01-Solution-Design-E09-E10-E11-E12.md) · [Requirements Traceability Matrix](../increment-05/02-Requirements-Traceability-Matrix.md) · [Release Notes](../increment-05/03-Release-Notes-Increment-5.md) · [API Contract](../increment-05/API-Contract-E09-E10-E11-E12.md) |
| **Companion documents** | 01 – Technology Architecture and Technology Selection · 02 – Security and SOC 2 Control Mapping · [04 – Technology Standards Comparison by Layer](04-Technology-Standards-Comparison-by-Layer.md) · [CI/CD Guide](../cicd/CI-CD-Guide.md) (section 10.5: checklist for a new module or feature) |
| **Workstation set-up** | [Developer Environment & IDE Setup Guide](../developer-setup/README.md) – JDK, Node, Docker, IntelliJ IDEA / VS Code / Eclipse / Dev Container, run configurations, scripts and the git hook (shipped separately as `WellnessZ-Developer-Environment-Setup.zip`) |
| **Client-specific work** | [Client Customisation Guide](../customisation/Client-Customisation-Guide.md) – configuration, feature flags, client migrations, SPI implementations in client modules, branching, promotion and governance |

This guide explains how to add a business module (an *E-number* from the logical architecture) to the base framework
without weakening its security properties. The running example is **E07 – Surveillance, statutory medicals &
immunisation**; replace names as appropriate. Everything referenced exists in the repository unless marked
*recommended*. Since increment 3, E07 exists for real (`modules/wz-e07-surveillance`, package `com.wellnessz.e07`):
Sections 2–9 keep the simplified teaching layout (`com.wellnessz.surveillance`), and Section 16 shows how E06, E07 and
E14 were actually built. Section 17 covers E04, E08, the increment-4 SPIs and the partner portal for external users.
Section 18 covers increment 5 – the final increment: E09 mental health and peer support, E10 drug and alcohol testing,
E11 travel health and communicable disease, E12 health promotion, the `SupportProgrammeReferral` SPI, the
duty-assignment feed and the coding rules for restricted classes. To set up a workstation first, follow the
[Developer Environment & IDE Setup Guide](../developer-setup/README.md); for work that only one client needs, read the
[Client Customisation Guide](../customisation/Client-Customisation-Guide.md) before writing code.

## 1. Orientation

### 1.1 Repository layout

| Path | Content |
|---|---|
| `pom.xml` | Parent/aggregator; Spring Boot 4.1.2 parent; managed versions (HAPI FHIR, POI, springdoc, ArchUnit …); `security` profile |
| `platform/wz-*` | Shared kernel and platform services (E15–E18, notifications), EHS intake, case kernel; since increment 2 the document service `platform/wz-documents` (Section 15.2) |
| `apps/wz-core-app` | Runnable core API; **all Flyway migrations** (`src/main/resources/db/migration`); ArchUnit and integration tests |
| `apps/wz-bff` | Backend-for-Frontend for browser apps |
| `modules/wz-e01-intake`, `modules/wz-e02-triage` | Business modules of increment 1 – the worked example in Section 14 |
| `modules/wz-e03-record`, `modules/wz-e05-fitness` | Business modules of increment 2 (OH record, fitness for duty) – the worked example in Section 15 |
| `modules/wz-e06-absence`, `modules/wz-e07-surveillance`, `modules/wz-e14-scheduling` | Business modules of increment 3 (absence & sickness, medical surveillance, scheduling & clinic operations) – the worked example in Section 16 |
| `modules/wz-e04-treatment`, `modules/wz-e08-claims` | Business modules of increment 4 (treatment coordination & partner referrals, claims coordination) – the worked example in Section 17 |
| `modules/wz-e09-mentalhealth`, `modules/wz-e10-substance`, `modules/wz-e11-travelhealth`, `modules/wz-e12-promotion` | Business modules of increment 5 (mental health & peer support, drug & alcohol testing, travel health & communicable disease, health promotion) – the worked example in Section 18 |
| `web/employee-app`, `web/manager-workspace`, `web/oh-workspace`, `web/partner-portal` | React front-ends (the OH clinical workspace was added in increment 1, the partner portal for external providers and adjusters in increment 4) |
| `tools/e2e` | Playwright end-to-end scripts: `e2e.js` (base framework), `e2e-increment1.js` (E01/E02, Section 14.7), `e2e-increment2.js` (E03/E05, Section 15.12), `e2e-increment3.js` (E06/E07/E14, Section 16.10), `e2e-increment4.js` (E04/E08 and the partner portal, Section 17.8), `e2e-increment5.js` (E09–E12, Section 18.9) |
| `deploy/local` | Docker Compose: PostgreSQL, Keycloak (realm import), Mailpit, GreenMail, OpenTelemetry collector |
| `config/` | SpotBugs exclusions and dependency-check suppressions (justification required) |
| `deploy/scripts/helm-release.sh`, `deploy/environments/README.md` | Helm release operations used by the DEV → UAT → PROD promotion (`cd.yml`, `deploy-env.yml`) and the environment matrix (CI/CD Guide, section 7) |
| `docs/developer-setup/` | Developer Environment & IDE Setup Guide and ready-made IDE files (IntelliJ IDEA, VS Code, Eclipse, Dev Container), scripts and the git hook |
| `docs/customisation/` | Client Customisation Guide |

Where a new business module fits:

![Component view](../images/03-component-view.png)

*Figure 1 – Components of the core API. New business modules sit in the business layer next to the case kernel and
depend only on platform modules; they talk to other business modules through events.*

### 1.2 Local environment

The short version follows; the full, IDE-specific walk-through (prerequisites per operating system, run and debug
configurations, scripts, troubleshooting) is the [Developer Environment & IDE Setup Guide](../developer-setup/README.md).

```bash
# 1. infrastructure (PostgreSQL 5432, Keycloak 8180, Mailpit 8025/1025, GreenMail IMAP 3143, OTel)
cd deploy/local && ./up.sh

# 2. core API (8080, management 8081) – local profile enables Swagger UI and relaxed TLS to the DB
export WZ_MASTER_KEY=$(openssl rand -base64 32)        # never reuse outside your machine
./mvnw -q -DskipTests install                          # build the reactor once (spring-boot:run with -am fails on the parent POM)
./mvnw -pl apps/wz-core-app spring-boot:run -Dspring-boot.run.profiles=local

# 3. BFF (8090) and a web app (5173 employee / 5174 manager / 5175 OH workspace / 5176 partner portal)
./mvnw -pl apps/wz-bff spring-boot:run -Dspring-boot.run.profiles=local
cd web/employee-app && npm ci && npm run dev
```

Test users in the local Keycloak realm `wellnessz` (password `ChangeMe!123`, temporary): `employee1`, `manager1`,
`hr1`, `clinician1`, `psych1`, `auditor1`, `admin1`; since increment 1 also `nurse1`, `nurse2`, `physician1`, `ame1`,
`cmo1`, `nursinghead1`, `hse1`, `crewsched1`, `hygiene1`, `reception1`, `firstaider1`; since increment 2 `dpo1`,
`partner1`; since increment 3 `scheduler1` (CLINIC_SCHEDULER), `biomed1` (BIOMEDICAL), `crewlicensing1`
(CREW_LICENSING) and `candidate1` (CANDIDATE, candidate id `C9001`); since increment 4 `claims1` (CLAIMS_COORDINATOR),
`adjuster1` (TPA_ADJUSTER, `/PARTNERS/GULFTPA`), `legal1` (LEGAL_COUNSEL), `finance1` (FINANCE), `procurement1`
(PROCUREMENT) and `physio1` (PARTNER_PROVIDER, `/PARTNERS/PHYSIOPLUS`; `partner1` is `/PARTNERS/CITYHOSP`). Partner and
adjuster users sign in on the partner portal (5176); the `local` profile switches the MFA gate off (Section 17.6).
Since increment 5 also `peer1` (PEER_SUPPORT_VOLUNTEER), `peercoord1` (PEER_SUPPORT_COORDINATOR), `safety1`
(SAFETY_MANAGER), `damanager1` (DA_PROGRAMME_MANAGER), `mro1` (MRO), `collector1` (COLLECTOR), `promo1`
(HEALTH_PROMOTION_LEAD), `ergo1` (ERGONOMIST) and `eap1` (PARTNER_PROVIDER, `/PARTNERS/EAP_WELLBEING`, partner portal).
The `local` profile loads synthetic HRIS
employees, the OH staff roster and the module demo data from `db/demo` (never enabled in shared environments).

### 1.3 The rules you must not break

1. **Clinical data stays in the OH domain.** Anything a manager, HR or an enterprise system sees is a status-only
   projection or an aggregate with small-cell suppression.
2. **Every read of OH data goes through `PolicyEngine`** and returns a projection chosen by the granted disclosure level.
3. **Business modules talk to each other through events**, never through each other's classes or tables.
4. **No PHI in logs, exception messages, problem details, audit reasons, notification texts or event payloads to
   non-OH destinations.**
5. **Clinical free text is stored with `EncryptedStringConverter`.**
6. **Schema changes only via Flyway**, with explicit least-privilege grants to `${app_role}`.
7. **Restricted classes stay restricted** (since increment 5): `RESTRICTED_CLINICAL` content is opened only by the named
   restricted-class clinician; everybody else gets `404`, at most a placeholder count, or status only (Section 18.4).
8. **Client-specific code never goes into product modules** – it lives in a client module of a client build
   (Client Customisation Guide).

## 2. Creating the Maven module

### 2.1 Location and naming

Create business modules under the top-level folder `modules/` (keeps `platform/` for shared services). Increment 1
created it with `modules/wz-e01-intake` and `modules/wz-e02-triage` (Section 14); increment 2 added `wz-e03-record` and
`wz-e05-fitness` (Section 15), increment 3 `wz-e06-absence`, `wz-e07-surveillance` and `wz-e14-scheduling` (Section 16).

```
modules/wz-e07-surveillance/
  pom.xml
  src/main/java/com/wellnessz/surveillance/
      SurveillanceModule.java           CaseModule registration
      api/SurveillanceController.java   REST endpoints (/api/v1/surveillance/**)
      app/SurveillanceService.java      transactional use cases
      domain/                           entities, value objects, repository interfaces
      events/                           DomainEvent records published by this module
      policy/SurveillancePolicies.java  AccessPolicy beans contributed by this module
      listener/                         idempotent event consumers
  src/test/java/com/wellnessz/surveillance/...
```

The package must be below `com.wellnessz` so that component scanning and JPA entity scanning of
`WellnessZCoreApplication` pick it up – no extra configuration is needed.

### 2.2 `pom.xml` template

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.wellnessz</groupId>
    <artifactId>wellnessz-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <artifactId>wz-e07-surveillance</artifactId>
  <name>WellnessZ OHCM :: E07 Surveillance, statutory medicals &amp; immunisation</name>

  <dependencies>
    <!-- platform services only; another business module only for an SPI, guarded by an ArchUnit rule (Section 14.1) -->
    <dependency><groupId>com.wellnessz</groupId><artifactId>wz-case-kernel</artifactId></dependency>
    <dependency><groupId>com.wellnessz</groupId><artifactId>wz-security</artifactId></dependency>
    <dependency><groupId>com.wellnessz</groupId><artifactId>wz-integration</artifactId></dependency>
    <dependency><groupId>com.wellnessz</groupId><artifactId>wz-masterdata</artifactId></dependency>
    <dependency><groupId>com.wellnessz</groupId><artifactId>wz-secrets</artifactId></dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

Then:

1. Add `<module>modules/wz-e07-surveillance</module>` to the root `pom.xml` (before `apps/wz-core-app`).
2. Add the artifact to `<dependencyManagement>` in the root `pom.xml` with `${project.version}`.
3. Add the dependency to `apps/wz-core-app/pom.xml`.
4. Extend `ArchitectureTest` (Section 9.3).

## 3. Registering the module – `CaseModule` SPI

`ModuleRegistry` lists E01–E18 with their delivery status. As soon as a Spring bean implementing `CaseModule` with the
same code exists, `GET /api/v1/modules` reports `IMPLEMENTED` and shows its capabilities.

```java
package com.wellnessz.surveillance;

import java.util.List;
import org.springframework.stereotype.Component;
import com.wellnessz.casekernel.module.CaseModule;

@Component
public class SurveillanceModule implements CaseModule {
    public static final String CODE = "E07";

    @Override public String code() { return CODE; }
    @Override public String name() { return "Surveillance, statutory medicals & immunisation"; }
    @Override public List<String> capabilities() {
        return List.of("hazard-based surveillance programmes", "recall scheduling", "immunisation record");
    }
}
```

### 3.1 Enablement flag `modules.eXX.enabled`

The flag lives in E18 configuration (`masterdata.config_entry`). Defaults: `true` for implemented/kernel modules,
`false` for planned ones. It can be changed with `PUT /api/v1/masterdata/config/modules.e07.enabled` (OH_ADMIN /
PLATFORM_ADMIN, audited). The guard is `ModuleRegistry.requireEnabled(code)`: when the module is disabled it throws an
`ApiException` that the global handler turns into **HTTP 503** with problem code `module_disabled` (fail closed). Every
module must call it on its entry points:

```java
@Service
class SurveillanceService {
    private final ModuleRegistry modules;
    ...
    @Transactional
    public EnrolmentView enrol(CurrentUser user, String employeeId, String hazardCode) {
        modules.requireEnabled(SurveillanceModule.CODE);      // 503 module_disabled when switched off
        ...
    }
}
```

Call it at the start of each use case (controller-facing service method) and scheduled job. In **event listeners**
do not throw – a disabled module would otherwise send the event to retry and eventually to `DEAD`. Follow the
pattern of the E01 `IncidentIntakeListener`, which checks `modules.isEnabled("E01")`, logs and leaves the source record
in place so it can be processed after re-enablement. The reference guard in the kernel is `CaseService.selfReferral` (E13); the
integration test `disabledModuleIsRefused` shows how to test it. Seed the default in your first
migration if the module should start disabled in production:

```sql
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at)
values ('modules.e07.enabled', 'false', 'E07 surveillance', 'migration', now())
on conflict (config_key) do nothing;
```

## 4. Data model and Flyway migrations

### 4.1 Conventions

| Rule | Detail |
|---|---|
| Location | `apps/wz-core-app/src/main/resources/db/migration`. Keep migrations in the app, not in the module jar: the Helm migration Job reads `/app/BOOT-INF/classes/db/migration` from the image, which does not include module jars. |
| Naming | `V<n>__<module>_<purpose>.sql`, e.g. `V6__e07_surveillance.sql` (V3–V5 are used by increment 1). Numbers are global and sequential; reserve the number in the PR title to avoid collisions. Never edit an applied migration – add a new one. |
| Schema | One schema per module (`surveillance`). Add it to `spring.flyway.schemas` in `apps/wz-core-app/src/main/resources/application.yml`, to `migrations.job.core.schemas` in `deploy/helm/wellnessz/values.yaml` **and** to the bootstrap scripts that create schemas and set `search_path`: `deploy/local/postgres/01-init-wellnessz.sh`, `deploy/sql/bootstrap-managed-postgres.sql`, `deploy/hybrid/cnpg/03-cluster.yaml` (init SQL and grants loop). Increment 1 did this for `triage`. |
| Demo data | Synthetic people for local work go in `apps/wz-core-app/src/main/resources/db/demo` as repeatable `R__*.sql` migrations; only the `local` and `test` profiles add `classpath:db/demo` to `spring.flyway.locations`. Other environments use `WZ_FLYWAY_LOCATIONS` (default `classpath:db/migration`). |
| Roles | Migrations run as the owner role (`WZ_DB_MIGRATOR_USERNAME`); the application connects as `${app_role}` (`WZ_DB_USERNAME`). Hibernate validates only (`ddl-auto: validate`). |
| Grants | End every migration with explicit, minimal grants to `${app_role}`. Omit `DELETE` where records are evidence; use `SELECT, INSERT` for append-only tables. |
| Types | `uuid` primary keys (generated in Java), `timestamptz` for instants, `date` for calendar dates, `varchar(n)` with explicit limits, `text` for encrypted columns (ciphertext is longer than plaintext). |
| Comments | Mark encrypted columns with `-- encrypted (field level)` and the classification. |
| Restricted class | Put restricted-class data (E09, E10) in its own schema so that DB-level grants and backups can be treated separately. |

### 4.2 Example migration

```sql
-- V6 – E07 surveillance programmes and results (classification: CLINICAL)
create schema if not exists surveillance;

create table surveillance.programme_enrolment (
    id             uuid primary key,
    employee_id    varchar(64)  not null,
    hazard_code    varchar(64)  not null references masterdata.hazard(code),
    org_unit_path  varchar(1000),
    next_due       date         not null,
    status         varchar(20)  not null check (status in ('ACTIVE','PAUSED','ENDED')),
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    version        bigint       not null default 0
);
create index ix_enrolment_due on surveillance.programme_enrolment (next_due) where status = 'ACTIVE';

create table surveillance.result (
    id             uuid primary key,
    enrolment_id   uuid         not null references surveillance.programme_enrolment(id),
    loinc_code     varchar(50),
    outcome        varchar(30)  not null,          -- FIT / FOLLOW_UP / REFER
    finding        text,                           -- encrypted (field level), CLINICAL
    finding_bidx   varchar(64),                    -- blind index (optional, for equality search)
    recorded_by    varchar(200) not null,
    recorded_at    timestamptz  not null
);

grant usage on schema surveillance to ${app_role};
grant select, insert, update on surveillance.programme_enrolment to ${app_role};
grant select, insert on surveillance.result to ${app_role};      -- results are evidence: no update/delete
```

### 4.3 Entities and field-level encryption

```java
@Entity
@Table(name = "result", schema = "surveillance")
public class SurveillanceResult {
    @Id private UUID id;
    @Column(name = "enrolment_id", nullable = false) private UUID enrolmentId;
    @Column(name = "loinc_code", length = 50) private String loincCode;
    @Column(nullable = false, length = 30) private String outcome;

    @Convert(converter = EncryptedStringConverter.class)       // AES-256-GCM, key version prefix v1:
    @Column(columnDefinition = "text") private String finding;

    @Column(name = "finding_bidx", length = 64) private String findingBlindIndex;  // FieldEncryptor.blindIndex(...)
    // recorded_by, recorded_at, constructor, getters – no setters for evidence fields
}
```

* Use the converter for **every** free-text or narrative clinical field.
* Need equality search on an encrypted value (e.g. a lab accession number)? Store `fieldEncryptor.blindIndex(value)`
  in a separate column and query by it. Never use `LIKE` on encrypted data.
* Do not encrypt columns used for ABAC scoping or joins (employee id, org path) – protect them with ABAC instead.
* Key rotation: changing `WZ_FIELD_KEY_VERSION` affects new writes only; old rows remain readable.

### 4.4 Classification

Assign a `DataClassification` to every resource type and derive it in code (as `CaseCategory.classification()` does).
Never accept a classification from a client.

## 5. Access control – contributing `AccessPolicy` beans

`PolicyEngine` collects all `AccessPolicy` beans. Combining: **deny policies first** (`isDenyPolicy() == true`, any deny
wins) → **highest permitted disclosure** among the remaining policies → **default deny**.

```java
@Configuration
class SurveillancePolicies {

    /** Surveillance nurses may read surveillance records (but never the restricted class). */
    @Bean
    AccessPolicy surveillanceNursePolicy() {
        return (user, action, r) -> "SURVEILLANCE_RESULT".equals(r.resourceType())
                && user.hasRole("OH_CLINICIAN")
                && r.classification() != DataClassification.RESTRICTED_CLINICAL
                ? Optional.of(AccessDecision.permit(DisclosureLevel.CLINICAL, "OH clinician - surveillance"))
                : Optional.empty();
    }

    /** Explicit deny: results may never be exported by non-OH roles. */
    @Bean
    AccessPolicy noExportOutsideOh() {
        return new AccessPolicy() {
            @Override public Optional<AccessDecision> evaluate(CurrentUser u, Action a, ResourceAttributes r) {
                return a == Action.EXPORT && !u.hasAnyRole("OH_CLINICIAN", "OH_ADMIN")
                        ? Optional.of(AccessDecision.deny("Export restricted to OH")) : Optional.empty();
            }
            @Override public boolean isDenyPolicy() { return true; }
        };
    }
}
```

Usage in the service:

```java
@Transactional(readOnly = true)
public Object get(CurrentUser user, UUID id) {
    SurveillanceResult res = results.findById(id).orElseThrow(() -> ApiException.notFound("Result"));
    ResourceAttributes attrs = new ResourceAttributes("SURVEILLANCE_RESULT", enrolment.employeeId(),
            enrolment.orgUnitPath(), DataClassification.CLINICAL);
    AccessDecision d = policy.decide(user, AccessPolicy.Action.READ, attrs);
    if (!d.permitted()) {
        audit.record("SURVEILLANCE_READ", "SURVEILLANCE_RESULT", id.toString(), AuditService.Outcome.DENIED, d.reason());
        throw ApiException.notFound("Result");         // 404, never confirm existence
    }
    audit.record("SURVEILLANCE_READ", "SURVEILLANCE_RESULT", id.toString(), AuditService.Outcome.SUCCESS,
            d.disclosure().name());
    return switch (d.disclosure()) {
        case STATUS_ONLY -> SurveillanceViews.Status.of(res, enrolment);     // record without clinical fields
        case SELF, CLINICAL, RESTRICTED_CLINICAL -> SurveillanceViews.Clinical.of(res, enrolment, d.disclosure());
        case NONE -> throw new IllegalStateException();
    };
}
```

Rules:

* Define **projection records per disclosure level**; the status-only record must not contain clinical fields at all
  (do not rely on `@JsonIgnore` or null-ing fields).
* Coarse role checks (`@PreAuthorize`) are allowed in addition, never instead of ABAC for OH data.
* Write a unit test per policy with `new PolicyEngine(List.of(...))` covering permit, deny and default deny
  (see `platform/wz-security/src/test/java/com/wellnessz/security/abac/PolicyEngineTest.java`).
* New roles (increment 1 added nine – use it as the checklist):
  1. the constant in `com.wellnessz.common.model.Roles` (and `OH_TEAM` / `FITNESS_DECIDERS` / `OH_TEAM_SPEL` if it is a
     clinical role) and, if it gets clinical disclosure, `DefaultPolicies.clinicianPolicy`;
  2. `deploy/local/keycloak/wellnessz-realm.json`: the realm role **and** the client scope mappings of
     `wellnessz-bff` and `wellnessz-mobile` – both clients have `fullScopeAllowed=false`, so a role missing from the
     scope mapping is silently dropped from tokens (found by the increment-1 E2E run); plus a test user;
  3. Entra ID: an app role on the `wellnessz-api` app registration and a group assigned to it (setup guide 06,
     Section 5.2), or `group-role-mapping` entries for group object ids;
  4. UI gating in the web apps (`src/auth/roles.ts`) and, if the role uses `/bff/api/workspace/**`, the BFF
     `WORKSPACE_ROLES`;
  5. the role table in document 02, Section 11.1, and the access-review scope.
* Restricted-class modules (E09, E10) must keep `DefaultPolicies.restrictedClassPolicy` effective and must not grant
  `RESTRICTED_CLINICAL` to any role other than `PSYCH_CLINICIAN` (or a new, equally restricted role approved by OH
  governance).

## 6. Events – publishing and consuming

### 6.1 Defining an event

```java
/** surveillance.recall.due.v1 – emitted when an employee is due for a statutory medical. */
public record RecallDue(String enrolmentId, String employeeId, String hazardCode, LocalDate dueDate)
        implements DomainEvent {
    @Override public String eventType() { return "surveillance.recall.due.v1"; }
    @Override public String aggregateType() { return "SURVEILLANCE_ENROLMENT"; }
    @Override public String aggregateId() { return enrolmentId; }
}
```

* Type names: `<module>.<aggregate>.<fact>.v<n>`; create `v2` for breaking changes and publish both during migration.
* **Flat records** of simple values. For enterprise destinations this is enforced: `OutboundPayloadGuard` rejects
  nested objects and arrays as well as any field not on the destination's allow-list (`disclosure_violation`).
* Payloads carry identifiers and status, not narratives. Consumers that need clinical data load it by id under ABAC.

### 6.2 Publishing

```java
@Transactional
public void scheduleRecall(...) {
    enrolments.save(enrolment);
    bus.publish(new RecallDue(...));                  // in-process subscribers (destination "internal")
}
```

`EventBus.publish` requires an active transaction (`Propagation.MANDATORY`): the outbox row commits atomically with your
change and is delivered at least once by `OutboxRelay`. Payloads are field-encrypted at rest in
`platform.outbox_event`; delivered rows are purged after `wellnessz.integration.outbox.retention` (default `P30D`), so a
replay is only possible within that window.

### 6.3 Publishing to an enterprise destination (status-only)

```java
bus.publish(new FitnessStatusChanged(...), "crew-rostering");
```

Before the first publish to a new destination:

1. Add an allow-list in `application.yml` – without it the publish fails (`destination_not_allowed`):
   ```yaml
   wellnessz.integration.outbound-allowlist:
     occupational-hygiene: [enrolmentId, employeeId, hazardCode, dueDate]
   ```
2. Add or update the `ConnectorDescriptor` in `ConnectorRegistry` (direction, protocol, data contract).
3. Implement or configure an `EventPublisher` that `supports(destination)` (broker adapter); it must be idempotent from
   the receiver's perspective (use `envelope.messageId()` as deduplication key) and must not log payloads.
4. Get the allow-list approved by the DPO (privacy review) – it is the contract of what leaves the OH domain.

### 6.4 Consuming idempotently

```java
@Component
class RecallListener {
    private final IdempotentConsumer consumer;
    private final JsonMapper json;          // tools.jackson.databind.json.JsonMapper (Jackson 3)

    @EventListener(condition = "#envelope.eventType() == 'surveillance.recall.due.v1'")
    public void onRecallDue(IntegrationEnvelope envelope) {
        consumer.handleOnce("notification.recall-reminder", envelope, () -> {
            RecallDue e = json.readValue(envelope.payload(), RecallDue.class);
            // ... side effects in this transaction ...
        });
    }
}
```

* The consumer name (`"<module>.<purpose>"`) is part of the de-duplication key in `platform.processed_message`; never
  rename it after go-live.
* The handler runs in a new transaction together with the de-duplication marker. Throwing an exception rolls both back
  and lets the relay retry with back-off; after 10 attempts the event becomes `DEAD` and is visible at
  `GET /api/v1/integration/events?status=DEAD`, replayable via `POST /api/v1/integration/events/{id}/replay`.
* Deserialise into **your own** DTO (tolerant of unknown fields), not into the producer's class – modules stay
  decoupled. The E01 `IncidentIntakeListener` (`modules/wz-e01-intake/.../rules/IncidentIntakeListener.java`) is the
  reference implementation.

### 6.5 Inbound APIs from external systems

For machine-to-machine `POST` endpoints, reuse `IdempotencyService.execute(scope, key, body, action)` so retries are
safe, and store raw inbound content via `InboundService.accept(...)` (encrypted) when it must be kept for
traceability. FHIR/HL7 messages already arrive through `wz-integration`; subscribe to
`integration.inbound.received.v1` and filter by `source`/`messageType`.

## 7. Audit

```java
audit.record("SURVEILLANCE_RESULT_RECORDED", "SURVEILLANCE_RESULT", id.toString(),
        AuditService.Outcome.SUCCESS, outcome.name());
```

| Rule | Detail |
|---|---|
| What to audit | Every read, list, create, update, export, consent check outcome, configuration change and denied attempt on OH data. |
| Action names | Upper snake case, `<RESOURCE>_<VERB>` (e.g. `CASE_READ`, `FITNESS_RECORDED`). Stable – auditors build queries on them. |
| Reason | Enum names, counts or disclosure levels only. **Never** names, diagnoses or free text. Reasons are sanitised and truncated to 500 characters, but sanitising is not anonymising. |
| System jobs | Use `audit.recordAs("system", "", action, ...)` when there is no authenticated user. |
| Transactions | `record` runs in `REQUIRES_NEW`, so denials are recorded even if your transaction rolls back. Do not call it in tight loops; aggregate (e.g. `returned=<n>`). |

## 8. API conventions

| Topic | Convention |
|---|---|
| Base path | `/api/v1/<module>/...` (e.g. `/api/v1/surveillance/enrolments`). Breaking changes → `/api/v2` alongside v1. |
| Auth | Every endpoint requires a bearer token (`/api/**` is `authenticated()`); add `@PreAuthorize` for role gates. Do not add `permitAll` rules. |
| Requests | Java records with Bean Validation (`@NotNull`, `@NotBlank`, `@Size`, `@Pattern`); validate ids with patterns; cap all strings. |
| Responses | Projection records per disclosure level; `jackson.default-property-inclusion: non_null`. |
| Errors | Throw `ApiException` (`notFound`, `forbidden`, `badRequest`, `conflict`); `GlobalExceptionHandler` produces **RFC 9457** problem details with `type urn:wellnessz:problem:<code>`, `code` and `correlationId`. Messages must not contain PHI. Use 404 for ABAC denials of a specific resource. |
| Lists | `limit` parameter with a hard cap (`Math.min(Math.max(limit, 1), 500)`); sort deterministically. |
| Idempotency | `Idempotency-Key` for M2M creates (Section 6.5); state transitions are naturally idempotent where possible. |
| Concurrency | `@Version` optimistic locking on mutable aggregates; map conflicts to 409. |
| SQL | Spring Data / JPQL or `JdbcTemplate` with bind parameters; dynamic columns only from an allow-list map (see `KpiService.DIMENSIONS`). |
| Time | Inject `Clock` (UTC bean in `CommonConfig`); store `Instant` as `timestamptz`. |
| OpenAPI | Generated by springdoc at `/v3/api-docs` (enabled only in the `local` profile or with `WZ_API_DOCS_ENABLED=true`). |
| Correlation | `X-Correlation-Id` is set by `CorrelationIdFilter`; include it in outbound calls you make. |

Example controller:

```java
@RestController
@RequestMapping("/api/v1/surveillance")
class SurveillanceController {

    record EnrolRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String employeeId,
                        @NotBlank @Pattern(regexp = "[A-Z0-9_\\-]{1,64}") String hazardCode) { }

    @PostMapping("/enrolments")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    EnrolmentView enrol(@Valid @RequestBody EnrolRequest r) {
        return service.enrol(users.require(), r.employeeId(), r.hazardCode());
    }
}
```

## 9. Testing

### 9.1 Test pyramid

| Level | Tooling | Location | Must cover |
|---|---|---|---|
| Unit | JUnit 5, AssertJ | module `src/test/java` | policies, rules, state machines, parsers, projections |
| Integration | `IntegrationTestBase` (Spring Boot, MockMvc, real PostgreSQL 16) | `apps/wz-core-app/src/test/java` | endpoint security per role, ABAC projections, encryption at rest, outbox payloads, audit entries, migrations |
| Architecture | ArchUnit 1.5.1 | `ArchitectureTest` | module boundaries |
| Web | Vitest 5 | `web/*/src/**/*.test.ts(x)` | API parsing, status-only whitelisting, auth flows, no-cache rules |

### 9.2 Integration tests against PostgreSQL

`IntegrationTestBase` uses the database from `WZ_TEST_DB_URL` (+ `WZ_TEST_DB_USERNAME`, `WZ_TEST_DB_PASSWORD`,
optional `WZ_TEST_DB_MIGRATOR_USERNAME`/`_PASSWORD`) when set – e.g. a CI service container – and otherwise starts
`postgres:16-alpine` with Testcontainers (Docker required). The `test` profile supplies throw-away keys, and
`IntegrationTestBase` generates `WZ_MASTER_KEY` / `WZ_PSEUDONYM_KEY` anew for every test JVM: encrypted columns are
keyed per run, so a persistent test database from `WZ_TEST_DB_URL` must be **dropped and recreated before every run**
(Section 16.9).

```java
class SurveillanceIntegrationTest extends IntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void managersNeverSeeFindings() throws Exception {
        // create as clinician, read as manager in scope -> status-only, no "finding" field
        mvc.perform(get("/api/v1/surveillance/results/" + id)
                .with(PlatformIntegrationTest.user("mgr", "M1", "/AIRLINE/OPS", "MANAGER")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.finding").doesNotExist());
        // stored ciphertext
        String stored = jdbc.queryForObject("select finding from surveillance.result where id = ?::uuid", String.class, id);
        assertThat(stored).startsWith("v1:");
    }
}
```

The package-private `user(...)` helper in `PlatformIntegrationTest` (package `com.wellnessz.app`) builds a JWT with `preferred_username`, `employee_id`, `org_scope`
and `ROLE_*` authorities – copy or reuse it. Run with `mvn -pl apps/wz-core-app -am verify`.

Mandatory integration cases for any module handling OH data:

1. Unauthenticated → 401; wrong role → 403.
2. Manager/HR in scope → status-only projection (assert that clinical fields are **absent**); out of scope → 404.
3. Clinician → clinical view; general clinician on restricted class → 404.
4. Clinical text encrypted at rest (`v1:` prefix, plaintext absent).
5. Outbound payloads to each destination contain only allow-listed fields.
6. Audit rows written for success and denial; `GET /api/v1/audit/verify` still `intact`.
7. Event consumers are idempotent (deliver the same envelope twice → one effect).
8. Module disabled (`modules.eXX.enabled=false`) → 503 `module_disabled`; listeners skip without dead-lettering.

### 9.3 ArchUnit rules for a new module

Extend `apps/wz-core-app/src/test/java/com/wellnessz/app/ArchitectureTest.java` (22 rules after increment 5; the
increment-5 rules are shown in Section 18.1, the rules for client packages in the Client Customisation Guide,
section 11.1):

```java
// platform must not depend on the new module
// -> add "com.wellnessz.surveillance.." to the resideInAnyPackage(...) list of platformDoesNotDependOnBusinessModules

@ArchTest
static final ArchRule surveillanceIsIsolated = noClasses()
        .that().resideInAPackage("com.wellnessz.surveillance..")
        .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.ehsintake..")   // + other business modules
        .because("business modules communicate via events only");
```

### 9.4 Quality gates

Locally: `mvn verify` (tests, JaCoCo, CycloneDX SBOM) and `mvn -Psecurity verify` (SpotBugs + FindSecBugs at effort
Max, OWASP dependency-check failing at CVSS ≥ 7; needs `NVD_API_KEY`). Web: `npm run lint && npm test && npm run build`
with `--max-warnings 0`. Suppressions in `config/` require justification, owner and expiry date.

In CI every pull request runs `.github/workflows/ci.yml` (Java build and tests with SBOM, web lint/test/build with
`npm audit` and SBOM, gitleaks, OWASP dependency-check, dependency review, Helm/Terraform/Trivy config checks) and
`codeql.yml` (Java and TypeScript). Images are built, Trivy-gated, signed and attested by `container.yml`; releases are
deployed by `cd.yml` through `deploy-env.yml`. The `java` CI job also runs SpotBugs + FindSecBugs and fails on any
finding. The increment-4 baseline is **275 Java tests** (17 ArchUnit rules), **0 SpotBugs findings** (6 justified
exclusions) and web tests oh-workspace 570 / employee-app 223 / manager-workspace 164 / partner-portal 119 with 0 lint
warnings (increment 1: 65 tests, 5 exclusions; increment 2: 128 tests, 6 exclusions; increment 3: 210 tests, 14 rules);
keep it that way. Before a release, also run the end-to-end scripts of all increments (Sections 14.7, 15.12, 16.10,
17.8).

## 10. Web and mobile feature additions

### 10.1 Employee app (`web/employee-app`)

1. Add a screen in `src/screens/` taking `ScreenProps` (`client`, `session`, `onSessionExpired`).
2. Add the route to `NAV` and the switch in `src/App.tsx` (hash routing via `useHashRoute`).
3. Call the API only through `client.api('/surveillance/...')` – paths are relative to `/api/v1`, work in BFF and native
   mode, send CSRF headers and use `cache: 'no-store'`.
4. Add response types and parsers to `src/api/types.ts` with tests; treat unknown fields as untrusted.
5. Use `useAsync(fn, onSessionExpired)` for loading state and session-expiry handling.
6. Never use `console.*`, `localStorage`, `sessionStorage` (ESLint fails the build) or put identifiers in URLs; log via
   `src/security/log.ts` with static event codes.
7. New MDM-configurable settings: add the key to `MANAGED_CONFIG_KEYS` (`src/config/managedConfig.ts`) and to
   `src/config/runtimeConfig.ts` with a safe default; document it for Intune / Workspace ONE administrators. Keys must
   not contain secrets.
8. Native capabilities (camera, files, push): add Capacitor plugins at exact versions, review permissions, keep
   `webContentsDebuggingEnabled: false`, and update the CSP (`VITE_CSP_CONNECT_SRC`) only for required origins.

### 10.2 Manager workspace (`web/manager-workspace`)

* Every API response rendered to managers/HR passes through a **field whitelist** in `src/api/statusOnly.ts` (defence
  in depth). Add new status fields there explicitly, with a unit test that an unexpected clinical field is dropped.
* Aggregates must use `src/api/kpi.ts` semantics: suppressed when `suppressed: true`, count missing or count below
  `minGroupSize`; never render a suppressed number.
* Role gating in `src/auth/roles.ts` is UX only – the BFF and the core API enforce access.

### 10.3 OH workspace (`web/oh-workspace`)

Same stack and conventions as the manager workspace (BFF only, hash routing, no browser storage, CSP, idle logout).
Screens live in `src/screens/` (case tabs in `src/screens/case/`, configuration in `src/screens/admin/`); role gating is
in `src/auth/roles.ts` (unit-tested). Two rules specific to clinical work:

* **The client never decides clinical outcomes.** The protocol runner (`src/domain/protocolRunner.ts`) only navigates
  and previews; the server's result is authoritative.
* **Never show state by colour alone** (SLA badges carry a symbol and text) and render suppressed aggregates as `<k`.

Dev server: `npm run dev` on port 5175 (proxy target `WZ_BFF_TARGET`, default `http://localhost:8090`), for example
`WZ_BFF_TARGET=http://localhost:8090 npx vite --port 5175`. See `web/oh-workspace/README.md` for the screen and test
inventory. Since increment 4, roles that never see clinical data (claims coordinator, finance, legal, procurement)
also use the OH workspace; their navigation must not offer clinical screens (unit-tested in `src/auth/roles.ts`), and
consent ids are chosen from pickers, never typed.

### 10.3a Partner portal (`web/partner-portal`, increment 4)

The fourth browser app, for **external** partner providers and TPA adjusters (dev port 5176). Same conventions, plus:
access is decided from the roles in `GET /bff/api/me` (MFA-gated in the core), API mappers whitelist contract fields,
and every screen carries a confidentiality hint. Details in Section 17.7 and `web/partner-portal/README.md`.

### 10.4 BFF

No change is normally needed – `/bff/api/**` proxies all `/api/v1/**` paths. If a module needs a role-gated alias,
follow `/bff/api/workspace/**`. Core endpoints that natively live under `/api/v1/workspace/...` must be listed in
`ApiProxyController.CORE_WORKSPACE_RESOURCES` (increment 2: `team-restrictions`, `duty-proposals`, `rtw-plans`,
`documents`; increment 3: `absence`, `advice-requests`, `surveillance`, `pre-employment`; increment 4: `claims`) and covered in
`ApiProxyPathTest`. The BFF does **not** parse request bodies: `spring.servlet.multipart.enabled=false` keeps
`multipart/form-data` uploads intact for the core (an increment-3 fix – with Spring's multipart parsing on, the proxy
forwarded an empty body and uploads failed with `400`). Validate uploads in the core only. Do not forward additional
headers without a security review.

## 11. Configuration

| Mechanism | Use for | Example |
|---|---|---|
| `application.yml` + environment variables | Technical settings per environment (12-factor) | `wellnessz.surveillance.recall-cron: ${WZ_SURV_RECALL_CRON:0 30 2 * * *}` |
| `ENC(...)` / `file:` | Secrets (passwords, client secrets, API keys) | `password: ${WZ_LAB_PASSWORD:}` with `ENC(...)` value or `file:/mnt/secrets/lab-password` (property name must contain password/secret/key/token/credential for `file:` resolution) |
| E18 `ConfigService` | Business configuration changed by OH admins without deployment (flags, SLA values, JSON form/rule definitions) | `config.flag("modules.e07.enabled", false)`, `config.get("surveillance.noise.interval-months")` |
| `@ConfigurationProperties` | Typed groups of settings | see `MailboxProperties`; `WellnessZSecurityProperties` (`mfa-required-roles`, `mfa-amr-values`, `mfa-acr-values` – increment 4) |

Never hard-code secrets or environment URLs. New secrets must be added to the Helm `secrets.core.files` list and the
cloud secret store via Terraform/External Secrets.

## 12. Coding and security standards

| Area | Standard |
|---|---|
| Logging | Log ids, counts, event types, exception **class names**. Never log payloads, names, addresses, free text or tokens. Wrap user-controlled values in `LogSanitizer.clean(...)`. No `System.out`/`err`. |
| Errors | No stack traces or SQL to clients; problem details only; messages generic. |
| Input | Validate everything at the boundary; cap sizes; parse file formats with hardened parsers (see `ExcelIncidentParser`); JSON-only for FHIR. |
| Output | Projection records per audience; status-only types contain no clinical fields. |
| Crypto | Use `FieldEncryptor`/`EncryptedStringConverter`/`AesGcmCipher` – never implement your own crypto or use ECB/CBC without MAC. Use `SecureRandom`. |
| Secrets | `ENC()`/`file:`; never in Git, images, logs, test fixtures (except throw-away keys in the `test` profile). |
| SQL | Bind parameters; allow-listed dynamic identifiers; no string concatenation of user input. |
| Concurrency | Scheduled jobs must be safe on multiple replicas (`FOR UPDATE SKIP LOCKED`, idempotency, or a scheduler lock). |
| Time zones | Store UTC; convert at the edge (crew operate across time zones – see `QuietHours`). |
| Notifications | Use approved templates only (`NotificationTemplates`); placeholders allow-listed; content must stay a content-free nudge. |
| Dependencies | Exact versions; managed in the root `pom.xml`; justify every new dependency (licence, maintenance, CVEs); update SBOM. |
| Front-end | No `dangerouslySetInnerHTML`; no inline scripts/styles (CSP); no PHI in caches, URLs or logs. |
| Restricted class | Separate schema, `RESTRICTED_CLINICAL` classification, no analytics inclusion without OH governance approval; named-clinician check with `404` for everyone else, placeholder counts only, status-only outputs, restricted documents via `generateSigned(…, DataClassification.RESTRICTED_CLINICAL)`, disclosure log for every status crossing the boundary – full rules in Section 18.4. |
| Aggregates | Every grouped output passes small-cell suppression (`wellnessz.analytics.min-group-size`, default 5; `SmallCellSuppression`); suppressed cells carry no numbers. |
| Client-specific behaviour | Never `if (client == "acme")` in product code; use configuration, feature flags read by the feature (`WZ_FEATURE_*`) or an SPI implemented in a client module (Client Customisation Guide). |

## 13. Definition of Done for a module increment

| # | Criterion |
|---|---|
| 1 | `CaseModule` bean registered; `GET /api/v1/modules` shows `IMPLEMENTED`; `ModuleRegistry.requireEnabled` on all use cases and jobs (listeners skip instead of throwing); a disabled-module test. |
| 2 | Data classification documented for each table/column; clinical free text encrypted; migration with least-privilege grants; schema added to Flyway and Helm migration settings. |
| 3 | `AccessPolicy` beans with unit tests; every read goes through `PolicyEngine`; projections per disclosure level; status-only record without clinical fields. |
| 4 | Audit events for reads, writes, exports and denials; no PHI in reasons. |
| 5 | Events versioned, flat, documented; consumers idempotent; outbound destinations allow-listed, catalogued in `ConnectorRegistry` and approved by the DPO. |
| 6 | API follows Section 8; OpenAPI reviewed; RFC 9457 errors. |
| 7 | Integration tests for the eight mandatory cases (Section 9.2); ArchUnit rules extended; all tests green. |
| 8 | CI green (`ci.yml`, `codeql.yml`); `mvn -Psecurity verify` clean or exceptions recorded with owner and expiry; no new Trivy/dependency-check findings. |
| 9 | Threat model (doc 02, Section 7) extended for new flows; DPIA screening done; retention period for new data defined. |
| 10 | Operational readiness: metrics/alerts for new jobs, runbook entries (dead letters, replay), configuration documented, MDM keys documented if any. |
| 11 | Documentation updated: module status in doc 01, control mapping in doc 02 if controls change; an end-to-end script and screenshots for the increment (`tools/e2e/`, `docs/images/screens/increment-NN/`); `docs/api/openapi.json` regenerated; the CI/CD Guide checklist (section 10.5) completed. |
| 12 | Peer review by a second developer and, for changes to security components, by the security champion. |

## 14. Worked example: how E01 and E02 plug in (increment 1)

Increment 1 delivered the first two real business modules. Use them as the reference when you build the next one;
everything below exists in the repository. Design background: [Solution Design E01/E02](../increment-01/01-Solution-Design-E01-E02.md);
endpoints: [API Contract](../increment-01/API-Contract-E01-E02.md).

### 14.1 Module wiring

| Step | E01 (`modules/wz-e01-intake`) | E02 (`modules/wz-e02-triage`) |
|---|---|---|
| Maven dependencies | `wz-case-kernel`, `wz-ehs-intake`, `wz-notification` | `wz-e01-intake` only (transitively the platform) |
| Root `pom.xml` | `<module>` + `<dependencyManagement>` entry | same |
| Registration | `com.wellnessz.e01.E01Module implements CaseModule` (code `E01`, capabilities E01-F01…F08) | `com.wellnessz.e02.E02Module` (code `E02`, E02-F01…F07) |
| Schema | existing `intake` (V4) plus kernel tables in `casemgmt` / `masterdata` (V3) | new schema `triage` (V5) |
| Enablement | `IncidentIntakeListener` checks `modules.isEnabled("E01")` and leaves the incident in intake | `modules.e02.enabled` seeded `true` by V4 |
| ArchUnit | `intakeDoesNotDependOnTriage`; both in `platformDoesNotDependOnBusinessModules` and `kernelDoesNotDependOnBusinessModules` | – |

E02 → E01 is the **only** dependency between business modules, and it exists for one SPI (`RecipientResolver`) and
the notification-rule types. It is allowed because the reverse direction is forbidden by an ArchUnit rule, so no
cycle can form. Any other cross-module need goes through events or a kernel SPI.

### 14.2 Kernel SPI: `CaseTransitionGuard`

The kernel must enforce module rules on status changes without knowing the modules. It injects
`List<CaseTransitionGuard>` and calls each guard before a transition:

```java
// platform/wz-case-kernel/.../support/CaseTransitionGuard.java
public interface CaseTransitionGuard {
    /** Throw {@code ApiException} (422) with a user-facing reason when the transition must not happen. */
    void check(OhCase c, CaseStatus next);
}
```

E01 implements it in `RecordabilityService` (an injury or illness case cannot leave `INTAKE` before recordability is
classified → `422 recordability_required`); E02 implements it in `SafetyService` (a safety-critical case cannot be
closed without a fitness decision and the checklist of its safety category → `422 fitness_required` /
`checklist_required`). Rules for your own guard:

* Keep it cheap and side-effect free; it runs inside the caller's transaction.
* Return quickly for transitions you do not care about (`if (next != CaseStatus.CLOSED) return;`).
* Use a stable problem `code`; the OH workspace maps it to the tab that fixes the problem.
* Callers that change status implicitly (for example `TriageService` moving `INTAKE` → `TRIAGE`) catch the
  `ApiException` and record a `STATUS_HELD` timeline entry instead of failing the user's action.

### 14.3 Module SPI: `RecipientResolver`

E01 owns the notification matrix (`intake.notification_rule`: event × source × case type → recipient, channel,
template) but not queues or staff. It defines the SPI and injects all implementations:

```java
// modules/wz-e01-intake/.../notify/RecipientResolver.java
public interface RecipientResolver {
    record Address(String recipientId, String email) { }
    List<Address> resolve(NotificationRule.Recipient recipient, OhCase c);
}
```

E02 contributes `OhRecipientResolver` (queue distribution list, case owner, Nursing Head). Stakeholder recipients
(HSE reporter, line manager, crew scheduling, hygiene manager) are resolved by E01 from the incident, the HRIS master
and E18 keys such as `notifications.crew-scheduling.email`. Pattern: **the module that owns the decision defines the
SPI; the module that owns the data implements it.**

### 14.4 Events between kernel, E01 and E02

| Event | Producer | Consumers |
|---|---|---|
| `ehs.incident.received.v1` | `wz-ehs-intake` (mailbox, Excel, webhook) | E01 `IncidentIntakeListener` (rule table) |
| `case.opened.v1` | kernel `CaseService` | E01 `DuplicateService` (duplicate candidates), E02 `AssignmentService` (queue, owner, `TRIAGE` timer), E01 `IntakeNotificationDispatcher` |
| `case.assigned.v1`, `case.escalated.v1`, `sla.at-risk.v1`, `sla.breached.v1` | E02 (`E02Events`) | E01 `IntakeNotificationDispatcher` |
| `case.triaged.v1` | E02 `TriageService` | none yet (available for E03/E05) |
| `fitness.status.changed.v1` | kernel `publishFitness` | outbound `hr-systems`, `crew-rostering`, `catering-hygiene` (allow-listed) |

All consumers use `IdempotentConsumer.handleOnce(...)` with a stable consumer name, deserialise into their own DTO
(for example `IncidentFacts` in E01) and check module enablement without throwing.

### 14.5 Adding a triage protocol

Protocols are versioned JSON documents (doc 01, Section 14.14). To add a new presentation:

1. Create `modules/wz-e02-triage/src/main/resources/triage/protocols/<CODE>.json` (`CODE` matches `[A-Z_]{1,40}`):

   ```json
   {
     "code": "DENTAL", "version": 1, "title": "Dental pain / injury", "presentation": "DENTAL",
     "caseType": "INJURY", "start": "q1", "status": "DRAFT",
     "note": "DRAFT clinical content for CMO / Nursing Head review – not for clinical use until approved",
     "questions": [
       { "id": "q1", "text": "Facial swelling spreading to the eye or neck, or difficulty swallowing?", "type": "BOOLEAN",
         "redFlag": true, "onYes": { "outcome": "EMERGENCY" }, "onNo": { "next": "q2" } },
       { "id": "q2", "text": "Pain score (0–10)", "type": "NUMBER", "min": 0, "max": 10,
         "rules": [ { "if": ">=7", "outcome": "SEMI_URGENT" }, { "else": true, "outcome": "ROUTINE" } ] }
     ],
     "outcomes": {
       "EMERGENCY":   { "disposition": "Call emergency services now", "adviceKey": "EMERGENCY_GENERIC" },
       "SEMI_URGENT": { "disposition": "OH nurse assessment within 4 hours", "adviceKey": "GENERIC_ROUTINE" },
       "ROUTINE":     { "disposition": "OH nurse appointment within 1 working day", "adviceKey": "GENERIC_ROUTINE" }
     }
   }
   ```

   Question types: `BOOLEAN` (`onYes` / `onNo`), `NUMBER` (`min`, `max`, `rules`), `SELECT` (`options` + `rules`
   with `==VALUE`). Rule grammar: `>=n <=n >n <n ==n =n !=n`, bare `n`, ranges `a..b` / `a-b`, `==true/false`,
   `else: true`. `caseType` must be a `CaseType` value; outcome keys are `Urgency` values. Every red-flag question must
   lead to `EMERGENCY`.
2. Add any new advice keys to `triage/advice.json` with `en`, `ar` and `fr` texts (English is the fallback).
3. Add unit tests next to `ProtocolEvaluatorTest`: red flag → `EMERGENCY`, each rule threshold, and an incomplete
   path (`IncompleteException`). The OH workspace runner (`web/oh-workspace/src/domain/protocolRunner.ts`) uses the
   same grammar; extend `protocolRunner.test.ts` if you add grammar.
4. Start the application. `ProtocolCatalog` seeds the file as `DRAFT` **only if the code does not exist yet**.
5. The CMO reviews it in *OH workspace → Admin → Protocols* and approves (`POST /api/v1/triage/protocols/{code}/approve`).

To publish a **new version of an existing protocol**, do not edit the seeded file in place (existing codes are never
re-seeded). Add a Flyway migration that inserts the new row into `triage.triage_protocol` with `version = n + 1`,
`status = 'DRAFT'` and the JSON in `definition`; after CMO approval the previously approved version becomes `RETIRED`.
Past assessments keep pointing at the version they used.

### 14.6 Adding or changing an intake rule

* **Business change (no release):** OH_ADMIN or the Nursing Head edits *OH workspace → Admin → Intake rules*
  (`POST` / `PUT /api/v1/intake/rules`). Test first with *Dry run* (`POST /api/v1/intake/rules/test` with a sample
  incident): it returns the rule that would fire or `NO_MATCH`. Lower `priority` numbers win; null conditions are
  wildcards; `AUTO_CREATE` needs a case type. Changes are audited.
* **Shipped default:** add a Flyway migration that inserts into `intake.intake_rule` (see the seed in
  `V4__e01_intake.sql`); never update rows an operator may already have changed.
* **New condition type** (for example "aircraft type"): this is a code change – add the column (migration), the field
  to `IntakeRule` and `IntakeRule.matches`, `IntakeRuleController.RuleRequest` / `RuleView`, `IncidentFacts` and the
  producing event (`IncidentReceived` in `wz-ehs-intake`, flat field), the webhook validation in
  `IncidentWebhookService`, the OH workspace rule editor, and tests in `RuleEngineTest`.

Assignment rules (`triage.assignment_rule`, *Admin → Assignment rules*, `POST /api/v1/assignment/rules/test`) and SLA
policies (`triage.sla_policy`, *Admin → SLA*) follow the same pattern.

### 14.7 Running the increment-1 end-to-end test

`tools/e2e/e2e-increment1.js` drives the increment through real Keycloak logins and the BFF and prints 18
`PASS`/`FAIL` lines (exit code 1 on any failure). It needs the local stack (`./deploy/local/up.sh`), core and BFF
(Section 1.2) and all three dev servers:

```bash
cd web/employee-app && npm run dev        # 5173
cd web/manager-workspace && npm run dev   # 5174
cd web/oh-workspace && npm run dev        # 5175
```

```bash
npm install --no-save playwright@1 && npx playwright install chromium
```

```bash
node tools/e2e/e2e-increment1.js
```

| Variable | Default | Meaning |
|---|---|---|
| `WZ_E2E_EMPLOYEE_URL` / `WZ_E2E_WORKSPACE_URL` / `WZ_E2E_OH_URL` | `http://localhost:5173` / `5174` / `5175` | Web app origins |
| `WZ_E2E_IDP_URL` | `http://localhost:8180` | Keycloak base URL (also used for the integration token) |
| `WZ_E2E_CORE_URL` | `http://localhost:8080` | Core API, called directly for the EHS webhook |
| `WZ_E2E_INTEGRATION_SECRET` | `change-me-local-only` | Local secret of client `wellnessz-integration` |
| `WZ_E2E_NEW_PASSWORD` | `Wz-Local-Demo-2026!` | Password set at the first (temporary-password) login; later runs log in with it |
| `WZ_E2E_OUT` | `tools/e2e/shots` | Screenshot directory (the published set is in `docs/images/screens/increment-01/`) |

The script relies on the demo people loaded by the `local` profile (`E1001`–`E1005`, `M2001`, the OH staff roster)
and on the realm users `employee1`, `manager1`, `nursinghead1`, `nurse1`, `admin1`, `reception1` and `crewsched1`.

## 15. Worked example: E03, E05 and the document service (increment 2)

Increment 2 added a platform service used by two business modules and four kernel / platform SPIs. Design background:
[Solution Design E03/E05](../increment-02/01-Solution-Design-E03-E05.md); endpoints:
[API Contract E03/E05](../increment-02/API-Contract-E03-E05.md).

### 15.1 Module wiring

| Step | Documents (`platform/wz-documents`) | E03 (`modules/wz-e03-record`) | E05 (`modules/wz-e05-fitness`) |
|---|---|---|---|
| Maven dependencies | `wz-case-kernel`, `wz-notification`, `org.apache.pdfbox:pdfbox` (version `pdfbox.version` = 3.0.8 in the root `pom.xml`) | `wz-e02-triage`, `wz-documents` | `wz-e02-triage`, `wz-documents` |
| Root `pom.xml` / core app | `<module>` + `<dependencyManagement>` entries; dependency in `apps/wz-core-app/pom.xml` | same | same |
| Registration | platform service (no `CaseModule`) | `E03Module` (code `E03`) | `E05Module` (code `E05`) |
| Schema / migration | `docs` (V6) | `ohrecord` (V7) | `fitness` (V8) |
| Demo data (`db/demo`, local / test only) | – | `R__demo_e03_record.sql` | `R__demo_e05_fitness.sql` |
| ArchUnit | `documentServiceIsAPlatformService` (no dependency on any business module) | `recordAndFitnessModulesAreIndependent` | `fitnessDoesNotDependOnRecord`; both covered by `earlierModulesDoNotDependOnLaterOnes` and the kernel / platform rules |

E03 and E05 do **not** depend on each other. Anything they share goes through the kernel (coded diagnosis on the
case, `ChartContributor`, `FitnessPublicationListener`), the document service or master data (fitness standards,
restriction catalogue, job-demand profiles in `wz-masterdata`).

### 15.2 Using `DocumentService`

Inject `com.wellnessz.documents.DocumentService` and work with its views; never write to `docs.*` directly.

```java
// generate a DRAFT from a template (merge fields validated, non-clinical templates screened by the clinical-text guard)
DocumentService.DocumentView doc = documents.generate(user, "RTW_PLAN", plan.getEmployeeId(), plan.getCaseId(), fields);
// sign: locks the draft, stores SHA-256(contentHash | signer | time), supersedes and un-shares older versions
documents.sign(user, doc.id());
// share: only signed documents; EMPLOYEE / MANAGER / HR (non-clinical templates only) / PARTNER (needs a consent id)
documents.share(user, doc.id(), DocumentShare.Target.EMPLOYEE, null, null);
documents.share(user, doc.id(), DocumentShare.Target.MANAGER, null, null);
```

| Method | Use | Access rule inside the service |
|---|---|---|
| `generate(user, templateCode, employeeId, caseId, fields)` | Draft from a template | OH roles: `EmployeeRecordAccess.requireClinical`; HR: own non-clinical templates within org scope |
| `newVersion(user, id, fields)` / `sign(user, id)` | Versioning and e-signature | Signer roles per template |
| `share(user, id, target, partnerOrg, consentId)` / `revokeShare(...)` | Disclosure (logged in `platform.disclosure_log`) | Clinical templates never to MANAGER / HR; PARTNER needs an active `TREATMENT_SHARING_PARTNER` consent |
| `scan(user, employeeId, caseId, documentType, bytes)` | Uploaded PDF / JPEG / PNG ≤ 10 MB; text layer or `OcrProvider` | OH roles or `CLINIC_RECEPTION` (files without reading) |
| `storePhoto(user, employeeId, caseId, bytes, title, classification)` | Clinical photograph (consent captured by the caller) | Never shareable |
| `search(user, employeeId, q)` | Blind-index search (doc 01, Section 14.17) | Clinical access; restricted class filtered |
| `list`, `get`, `content`, `sharedWithMe`, `sharedWithWorkspace`, `sharedWithPartner`, `acknowledge` | Reads | Audited; non-OH callers only see what was shared with them |

Real call sites: `RtwService` (RTW plan to employee and manager), `JobOfferService` (HR generates, signs and issues the
job offer), `ResultService` (result letter), `FitnessDecisionService` / `CrewService` (certificates and crew medical
reports). Do not catch and swallow the `ApiException`s these methods throw – they carry the user-facing reason.

### 15.3 Kernel SPI: `ChartContributor`

The chart (E03) shows sections owned by other modules without depending on them:

```java
// platform/wz-case-kernel/.../support/ChartContributor.java
public interface ChartContributor {
    record Alert(String kind, String severity, String text) { }
    record Contribution(Map<String, Object> sections, List<Alert> alerts) {
        public static final Contribution EMPTY = new Contribution(Map.of(), List.of());
    }
    Contribution contribute(CurrentUser user, String employeeId);
}
```

`ChartService` injects `List<ChartContributor>`, calls each after its own record-level access check, merges the
sections and alerts, and logs (without content) and skips a contributor that throws. E05's `FitnessChartContributor`
contributes `fitness` and `restrictions` and alerts such as `FITNESS_SIGNOFF_PENDING` or `RESTRICTION_LAPSED`. Rules:
return `Contribution.EMPTY` for callers outside the OH team, keep it read-only (`@Transactional(readOnly = true)`) and
cheap, use stable section keys, and put no restricted-class content into a section unless you check
`EmployeeRecordAccess.canSeeClinical(..., RESTRICTED_CLINICAL)` yourself.

### 15.4 Kernel SPI: `FitnessPublicationListener`

```java
// platform/wz-case-kernel/.../FitnessPublicationListener.java
public interface FitnessPublicationListener {
    void rosterStatusPublished(OhCase c, CaseEvents.RosterStatusChanged message);
}
```

`CaseService.publishFitness` builds the status-only `CaseEvents.RosterStatusChanged` (message id, roster status
FIT / LIMITED / UNFIT / PENDING via `FitnessFeed.rosterStatus`, validity dates, operational limitation codes), puts it
on the outbox for `crew-rostering` and calls every listener **inside the same transaction**. E05's `RosterFeedLog`
stores the message for the pull feed, acknowledgement and reconciliation. A listener must not call external systems
or throw for expected situations – an exception rolls back the fitness decision. Pass extra feed details with
`FitnessFeed` rather than adding clinical fields to the event; the outbound allow-list (`application.yml`) rejects
unknown fields.

### 15.5 Record-level access: `EmployeeRecordAccess`

Use it for every record that belongs to an employee rather than a case:

```java
Employee e = records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "CHART_READ");
// restricted class (mental health, restricted result codes)
records.requireClinical(user, employeeId, DataClassification.RESTRICTED_CLINICAL, AccessPolicy.Action.READ, "ENCOUNTER_READ");
// manager / HR views (status only, org scope)
records.requireStatus(user, employeeId, "TEAM_RESTRICTIONS_READ");
// filter lists instead of failing
boolean showRestricted = records.canSeeClinical(user, records.employee(employeeId), DataClassification.RESTRICTED_CLINICAL);
```

A denial is audited with your action name and surfaces as `404`. Break-glass needs nothing in your code: the ABAC
policies evaluate the active grant. Add a negative test for every non-OH role that could reach the endpoint (pattern:
`Increment2RecordIntegrationTest.f08HrManagersEmployeesAndReceptionNeverOpenClinicalRecords`).

Related platform hooks: implement `com.wellnessz.security.consent.ConsentWithdrawalListener` (idempotent, runs inside
the withdrawal transaction) if your module processes data under a consent – see `DocumentConsentListener`; use
`com.wellnessz.integration.JobRunGuard.tryStart(jobName, runKey)` to run a daily job once across replicas
(`platform.scheduled_job_run`) – see `FitnessJobs` and `RetentionService`.

### 15.6 Adding a document template

Templates are code (`platform/wz-documents/.../DocumentTemplates.java`), reviewed like code:

1. Add a `Template(code, type, title, audience, containsClinical, recordClass, generatorRoles, signerRoles, mergeFields,
   body)` entry. Merge fields: `f(key, label)` required (≤ 400 chars), `o(...)` optional, `t(key, label, required)`
   long text (≤ 4,000 chars); body paragraphs use `{{key}}` placeholders (letters only).
2. Set `containsClinical` honestly. `false` means the document may go to managers / HR and every merge field is screened
   by `ClinicalContentException.screen`; `true` means OH and the employee (and partners with consent) only.
3. Choose the record class for retention (`recordClassFor`) – a new class also needs a `docs.retention_schedule` row
   (Flyway migration, legal basis marked *Proposed*) and DPO approval.
4. If HR may issue it, add `Roles.HR` to generator and signer roles; HR can then only share it with the employee.
5. If notifications differ from `document.shared`, add a content-free template in `NotificationTemplates` (allowed
   placeholders only).
6. Extend the OH workspace document form (`web/oh-workspace/src/screens/documents/DocumentsPanel.tsx`) and tests;
   add an integration test that generates, signs and shares the template and, for non-clinical templates, proves that
   clinical wording is rejected (`certificateIsGeneratedSignedAndSharedWithoutClinicalContent`).
7. Wording is a draft until the DPO / CMO approve it (doc 01, O14).

### 15.7 Adding an encounter template or a new template version

Encounter templates are versioned JSON in `modules/wz-e03-record/src/main/resources/encounter-templates/` (loaded by
`EncounterTemplates` from `classpath*:encounter-templates/*.json`; governance status in `ohrecord.encounter_template`).

1. **New version of an existing type:** add a file (for example `INJURY_v2.json`) with the same `type` and
   `"version": 2`. Never edit a released version – encounters store the template version they used.
2. Fields: `key`, `label`, `type` (`TEXT`, `TEXTAREA`, `NUMBER` with optional `unit`, `DATE`, `BOOLEAN`, `SELECT`,
   `MULTISELECT` with `options`, `CODE` with `codeSystem`), `required`. Template flags: `bodyMap`, `bodyMapRequired`,
   `requiresDiagnosis`, `fitnessStandardPrompt`, `protocolPrompts`. Required fields are checked on signing
   (`ClinicalEncounterService.missingFields`).
3. Add a Flyway migration inserting `(type, version, 'DRAFT')` into `ohrecord.encounter_template`; without the row the
   template is shown as DRAFT and cannot be approved.
4. The CMO approves it in *OH workspace → Admin → Encounter templates*
   (`POST /api/v1/encounter-templates/{type}/approve`, audited `ENCOUNTER_TEMPLATE_APPROVE`).
5. **New encounter type** is a code change: add it to `EncounterTemplates.TYPES`, the `TYPES` pattern in
   `ClinicalEncounterService` and the controller validation, decide its classification (only `MENTAL_HEALTH` is
   restricted today), and extend the OH workspace types (`web/oh-workspace/src/domain/encounter.ts`) and tests
   (`EncounterRulesTest`, `Encounter.test.tsx`).

### 15.8 Adding or changing a fitness standard

Fitness standards live in `masterdata.fitness_standard` (`code`, `version`, `title`, `role_category`, `source`,
`reference_url`, `validity_months`, `validity_months_over_40`, `requires_signoff_role` `AME`/`CMO`,
`limitation_codes`, `status`).

* **Business change (no release):** OH_ADMIN or the CMO uses *OH workspace → Admin → Fitness standards*
  (`PUT /api/v1/masterdata/fitness-standards/{code}`). Each save creates a **new version** and marks the previous one
  `SUPERSEDED` (audited `FITNESS_STANDARD_VERSION`); past decisions keep the version they were made against.
* **Shipped default:** a Flyway migration inserting version 1 (see the seed in `V6__increment2_platform.sql`); never
  update rows an operator may have versioned.
* Limitation codes must exist in the crew limitation-code list (`LimitationCodes`, `GET /api/v1/crew/limitation-codes`)
  when the standard is used for crew; `requires_signoff_role` routes decisions to `AWAITING_SIGNOFF`.

### 15.9 Adding a job-demand profile or a restriction

* **Job-demand profiles** (`masterdata.job_demand_profile`): `PUT /api/v1/masterdata/job-demands/{jobCode}`
  (OH_ADMIN, NURSING_HEAD) or bulk `POST /api/v1/masterdata/job-demands/import` (OH_ADMIN, ≤ 2,000 profiles). `demands`
  is a JSON object over the fixed dimensions: numeric 0–500 for `LIFT_KG`, `PUSH_PULL_KG`, `HOURS_PER_DAY`,
  `SCREEN_HOURS`; boolean for `CLIMB`, `AIRSIDE_DRIVE`, `NIGHT`, `KNIFE`, `CHEMICAL`, `RESPIRATOR`, `NOISE`, `HEAT`,
  `HEIGHT`, `FLIGHT_DUTY`, `FOOD_HANDLING`, `SAFETY_CRITICAL`. Record who validated the profile (`validatedBy`).
* **Restrictions** (`masterdata.restriction`, *Admin → Restriction catalogue*,
  `PUT /api/v1/masterdata/restrictions/{code}`) map a functional label to one dimension; `MatchingEngine` compares
  restrictions with profiles to suggest compatible duties.
* **New dimension** is a code change: add it to `NUMERIC_DIMENSIONS` / `BOOLEAN_DIMENSIONS` in
  `FitnessMasterDataController`, to `MatchingEngine` (and `MatchingEngineTest`) and to the OH workspace admin screen.

### 15.10 Status-only outputs from a module

Follow the E05 pattern (`E05Events`): a flat event record, a destination constant (`hr-systems`, `catering-hygiene`,
`crew-rostering`) and the matching field list under `wellnessz.integration.outbound-allowlist.<destination>` in
`apps/wz-core-app/src/main/resources/application.yml`. Fields not in the list are rejected by `OutboundPayloadGuard`.
Have the DPO approve every list change (doc 02, Section 10).

### 15.11 Machine endpoints for gateways

Put endpoints that only gateways call under `/api/v1/integration/**` with `@PreAuthorize("hasRole('INTEGRATION')")`
(the platform filter already limits the path to `INTEGRATION`, `PLATFORM_ADMIN`, `OH_ADMIN`). Prefer **pull with
acknowledgement** (the LIS order queue, the roster feed) so the core needs no outbound connections. If people need the
same data, add an alias outside `/integration` (for example `/crew/roster-feed` for `CREW_SCHEDULING`) – never widen
the integration path rule.

### 15.12 Running the increment-2 end-to-end test

`tools/e2e/e2e-increment2.js` drives E03 and E05 through real Keycloak logins and the BFF and prints 25
`PASS`/`FAIL` lines (exit code 1 on any failure). Prerequisites as in Section 14.7: local stack, core with the `local`
profile (demo people, demo chart of `E1004`, crew profiles), BFF and the three dev servers. A realm imported before
increment 2 lacks `dpo1`: run `./deploy/local/down.sh --purge` and `./deploy/local/up.sh` once.

```bash
node tools/e2e/e2e-increment2.js
```

It uses the same environment variables as `e2e-increment1.js` (Section 14.7; screenshots go to `WZ_E2E_OUT`, the
published set is in `docs/images/screens/increment-02/`) and the realm users `employee1`, `manager1`, `hr1`, `nurse1`,
`physician1`, `ame1`, `cmo1`, `crewsched1`, `dpo1` and `admin1`. It seeds a pilot incident through the EHS webhook
(client `wellnessz-integration`), so `WZ_E2E_CORE_URL` and `WZ_E2E_INTEGRATION_SECRET` must match the environment.
Run all increment scripts before a release (CI/CD Guide, Section 3.1).

## 16. Worked example: E06, E07, E14 and the increment-3 kernel SPIs (increment 3)

Increment 3 added three business modules that need each other's behaviour – booking, attendance, calibration, results,
clearance, absence signals and capacity – without depending on each other's classes. The answer is seven small SPIs in
the kernel (`platform/wz-case-kernel/src/main/java/com/wellnessz/casekernel/support/`). Use this section as the
reference when a new module needs behaviour that another module owns. Design background:
[Solution Design E06/E07/E14](../increment-03/01-Solution-Design-E06-E07-E14.md); endpoints:
[API Contract E06/E07/E14](../increment-03/API-Contract-E06-E07-E14.md).

### 16.1 Module wiring

| Step | E06 (`modules/wz-e06-absence`) | E07 (`modules/wz-e07-surveillance`) | E14 (`modules/wz-e14-scheduling`) |
|---|---|---|---|
| Package | `com.wellnessz.e06` | `com.wellnessz.e07` | `com.wellnessz.e14` |
| Maven dependencies | `wz-e05-fitness` (functional-text screening and fitness support), `wz-documents` | `wz-e03-record`, `wz-e05-fitness`, `wz-documents` | `wz-e02-triage`, `wz-documents` |
| Root `pom.xml` / core app | `<module>` + `<dependencyManagement>`; dependency in `apps/wz-core-app/pom.xml` | same | same |
| Registration | `E06Module` (code `E06`) | `E07Module` (code `E07`) | `E14Module` (code `E14`) |
| Schema / migration | `absence` (V10); platform additions in V9 | `surveillance` (V11) | `scheduling` (V12) |
| Demo data (`db/demo`) | `R__demo_e06_absence.sql` | `R__demo_e07_surveillance.sql` | `R__demo_e14_scheduling.sql` |
| ArchUnit | `absenceIsIndependentOfSurveillanceAndScheduling` | `surveillanceIsIndependentOfAbsenceAndScheduling` | `schedulingIsIndependentOfClinicalModules` |
| Jobs (`JobRunGuard`) | `absence-feed`, `absence-triggers`, `absence-lta` | `surveillance-due`, `crew-certificates`, `pre-employment-purge` | `appointment-reminders`, `appointment-no-show`, `equipment-calibration` |
| Integration test | `Increment3AbsenceIntegrationTest` | `Increment3SurveillanceIntegrationTest` | `Increment3SchedulingIntegrationTest` |

`increment2ModulesDoNotDependOnIncrement3` keeps E03 and E05 free of the new modules: they call the new behaviour only
through SPIs. Remember to add every new module package to the platform, kernel and document-service rules in
`ArchitectureTest` (Section 9.3) – increment 3 did this for `e06`, `e07`, `e14`.

### 16.2 The increment-3 SPIs at a glance

| SPI | Provider of the behaviour (implements) | Consumer (calls) | How the consumer injects it | Behaviour when no implementation is deployed |
|---|---|---|---|---|
| `AppointmentBooking` | E14 `AppointmentBookingAdapter` | E07 `Booker`, E06 `LtaService` | `ObjectProvider<AppointmentBooking>` | Callers record "invited, not booked" |
| `AppointmentStatusListener` | E07 `SurveillanceSpi`, E06 `AbsenceCapacityDemand` | E14 `StatusNotifier` | `ObjectProvider<AppointmentStatusListener>` (all beans) | Nobody is notified |
| `DeviceCalibrationCheck` | E14 `EquipmentCalibrationCheck` | E03 `ResultService` | `List<DeviceCalibrationCheck>` | Every device accepted |
| `ResultFollowUpListener` | E07 `FollowUpService` | E03 `ResultService` | `List<ResultFollowUpListener>` | No follow-up evaluation |
| `FitnessClearanceGuard` | E07 `RaiService` | E05 `FitnessDecisionService` | `List<FitnessClearanceGuard>` | No clearance check |
| `AbsenceSignalListener` | E07 `RaiService` | E06 `LtaService` | `List<AbsenceSignalListener>` | No signal |
| `CapacityDemandSource` | E07 `SurveillanceSpi`, E06 `AbsenceCapacityDemand` | E14 `CapacityService` | `ObjectProvider<CapacityDemandSource>` | Supply without demand |

Rules that apply to all of them:

1. **Optional by construction.** Inject a `List<…>` or `ObjectProvider<…>`, never a single required bean, so the
   consumer starts and works when the provider module is absent (E2E and integration tests run with all modules, unit
   tests without).
2. **Identifiers, codes and counts only.** No free text, reason, diagnosis or person-level data beyond ids crosses an
   SPI (`CapacityDemandSource` is counts per site / service / month; `AbsenceSignalListener` never receives the
   absence reason; `FitnessClearanceGuard` returns a reason that contains **no clinical content** because it is shown
   in the `422` problem detail).
3. **Same transaction, no surprises.** Listeners run inside the caller's transaction. Do not call external systems, do
   not swallow errors silently, and throw only for a real veto (`DeviceCalibrationCheck`, `FitnessClearanceGuard`
   return a verdict instead of throwing). E06 books the first LTA contact in a separate transaction
   (`TransactionTemplate` with `REQUIRES_NEW`) so a full diary cannot roll back the pathway.
4. **Filter with `supports`.** `AppointmentStatusListener.supports(sourceType)` limits callbacks to the appointments a
   module created (`sourceType` such as `SURVEILLANCE_INVITATION`, `PRE_EMPLOYMENT`, `IMMUNISATION`, `CREW_RENEWAL` for E07 and
   `ABSENCE_CONTACT` for E06; `sourceRef` = the module's own id).
5. **Audit as the module.** Use `audit.recordAs("system", "MODULE", …)` for actions taken in a callback.

### 16.3 Booking through `AppointmentBooking`

```java
// E07 support/Booker.java – the pattern to copy
@Component
public class Booker {
    private final ObjectProvider<AppointmentBooking> booking;
    public Booker(ObjectProvider<AppointmentBooking> booking) { this.booking = booking; }

    public boolean available() { return booking.orderedStream().findFirst().isPresent(); }

    public Optional<AppointmentBooking.Booking> book(String actor, AppointmentBooking.BookingRequest request) {
        return booking.orderedStream().findFirst().flatMap(b -> b.bookNext(actor, request));
    }
}

// a caller – DueService.invite (abridged): no booking → the invitation stays INVITED
Optional<AppointmentBooking.Booking> b = booker.book(actor, new AppointmentBooking.BookingRequest(e.getEmployeeId(),
        p.getServiceCode(), siteCode, earliest, campaignId, SOURCE_TYPE /* SURVEILLANCE_INVITATION */, i.getId().toString(), null));
if (b.isPresent()) {
    i.setStatus("BOOKED");
    i.setAppointmentId(b.get().appointmentId());
}
```

`serviceCode` must exist in the E14 service catalogue (`scheduling.appointment_type`, for example `AUDIOMETRY`,
`ABSENCE_REVIEW`, `VACCINATION`, `TELEHEALTH_CONSULT`); add new types with a migration or *OH workspace → Clinic →
Service catalogue* (OH_ADMIN, Nursing Head). Bookings made through the SPI follow the same conflict rules as interactive
bookings.

### 16.4 Implementing a listener or a guard

```java
// E07 followup/RaiService.java (abridged) – one bean implementing two SPIs
@Service
public class RaiService implements AbsenceSignalListener, FitnessClearanceGuard {

    static final String BLOCKING_REASON =                       // shown in the 422 – no clinical content
            "A return-after-illness medical is required before this employee returns to a safety-critical role";

    @Override
    public void longTermAbsence(String employeeId, UUID caseId, LocalDate startDate, long days) {
        Employee e = support.employee(employeeId).orElse(null);
        if (e == null || !support.isSafetyCritical(e) || rai.existsByEmployeeIdAndStatus(employeeId, REQUIRED)) {
            return;                                                  // only safety-critical jobs, once
        }
        ReturnAfterIllness r = create(employeeId, "LONG_TERM_ABSENCE", "E06", caseId, "system");
        audit.recordAs("system", "MODULE", "RAI_REQUIRED", "RAI", r.getId().toString(), AuditService.Outcome.SUCCESS, "source=E06 days=" + days);
    }

    @Override
    public Optional<String> blockingReason(String employeeId, String outcome) {
        return rai.existsByEmployeeIdAndStatus(employeeId, REQUIRED) ? Optional.of(BLOCKING_REASON) : Optional.empty();
    }
}
```

`FitnessDecisionService` turns a non-empty reason into `422 clearance_required`. Follow the same shape for
`DeviceCalibrationCheck` (return `Status(verdict, calibrationId, dueOn)`; E03 maps `OUT_OF_CALIBRATION` /
`OUT_OF_SERVICE` to `422`) and `ResultFollowUpListener` (evaluate `RecordedResult` codes and flags; create follow-ups
inside the transaction). Integration tests that prove the contract end to end:
`f07ReturnAfterIllnessBlocksFitDecisionUntilCompleted`, `f04EquipmentCalibrationBlocksE03DeviceCapture`,
`f07DeviceStsResultRaisesFollowUpAndCase`.

### 16.5 Writing an `AbsenceFeedConnector`

E06 ships `NoopAbsenceFeedConnector`. A site adapter is one more bean in E06 (or in a separate integration module that
depends on E06):

```java
@Component
class TnaAbsenceFeedConnector implements AbsenceFeedConnector {
    @Override public String sourceSystem() { return "TNA"; }       // HRIS, TNA, CREW or MANUAL

    @Override public List<FeedRow> pull(LocalDate since) {
        // read the source (SFTP CSV, REST, database view) with credentials from ENC(...) / file: secrets
        return rows.stream().map(r -> new FeedRow(r.ref(), r.badgeOrEmployeeId(), r.start(), r.end(),
                r.type(), r.reasonCategory(), r.certified())).toList();
    }
}
```

The nightly `absence-feed` job calls every connector and runs the rows through the same reconciliation as the push
endpoint (`POST /api/v1/integration/absence/episodes`): unknown employees become `feed_mismatch` rows for OH admin;
only allow-listed absence types and reason **categories** are accepted – map source codes to categories in the
adapter and never pass diagnosis text. Add a unit test for the mapping and an integration test with a stub source.
Outbound egress for the adapter needs a `networkPolicy.extraEgress.core` rule.

### 16.6 Module-level `DocumentService` API

Increment 2's `DocumentService` methods take a `CurrentUser` and apply OH access rules. Modules sometimes act for a
workflow that they have **already authorised** – an employee uploading their own certificate, a candidate, a nightly
job – so increment 3 added four methods that take an actor name instead:

| Method | Use | Rules that still apply |
|---|---|---|
| `storeUpload(actor, employeeId, caseId, documentType, title, bytes, classification)` | Employee / candidate / integration upload (`SICK_CERTIFICATE`, `PRE_EMPLOYMENT_DOCUMENT`, `VACCINATION_RECORD`, `EXTERNAL_AEROMEDICAL_REPORT`, `EXTERNAL_REPORT`); the employee id may be a candidate id | Magic bytes, size, text extraction, hashing, blind index, encryption |
| `generateSigned(actor, templateCode, employeeId, caseId, fields)` | Generate and sign in one step (released `OH_ADVICE_REPORT`, `VACCINATION_CERTIFICATE`, `PRE_EMPLOYMENT_OUTCOME`) | Merge-field validation and clinical-text screening exactly as `generate` |
| `shareAsSystem(actor, id, target)` | Share as a workflow step (employee consented to release; employee requested a certificate) | Non-clinical rule for MANAGER / HR, disclosure log; **no partner shares** |
| `systemContent(actor, id)` | Content for a module endpoint that has authorised the reader (a candidate's own upload, a certificate for OH) | Audited `DOCUMENT_DOWNLOAD` as `MODULE` |

**Authorise before you call.** These methods do not check the caller's role or scope – the module endpoint must do
that first (`@PreAuthorize` plus `EmployeeRecordAccess` or an ownership check). Calibration certificates are not
employee documents and therefore are stored in `scheduling.calibration` (`bytea` + SHA-256), not in `docs`.

### 16.7 Status-only views, suppression and disclosure logging

* **New roles get projections, not filters.** Give schedulers, reception, crew licensing and HR their own record
  types (e.g. appointment with type and time but no reason; certificate with `validity` only). Never return the OH type
  and null out fields.
* **Log disclosures of named status.** When a manager, HR or the hygiene manager sees health-related status of a named
  employee (surveillance compliance, immunisation status, pre-employment outcome), call
  `DisclosureLogService.record(employeeId, recipientType, recipientName, resourceType, resourceId, title, purpose,
  consentId, actor)` – see `DueService.team` and `ImmunisationService`.
* **Aggregate with the minimum group size.** Read `wellnessz.analytics.min-group-size` and publish groups below it as
  `suppressed: true` without counts (`UnitAggregator`, `ComplianceMaths`); add a unit test with a group of *k − 1*.
* **Screen functional text.** Text that leaves OH (advice reports, HR stage notes, admin notes on appointments,
  equipment status reasons) goes through `FitnessSupport.functional(...)` / `ClinicalContentException.screen(...)`
  → `422 clinical_content_not_allowed`.

### 16.8 Scheduled jobs and notification templates

Jobs follow `AbsenceJobs` / `SurveillanceJobs` / `SchedulingJobs`: `@Scheduled(cron = "${wellnessz.<module>.<job>-cron:…}",
zone = "UTC")`, then `if (guard.tryStart("<job-name>", runKey))` with the date (daily) or the quarter-hour bucket
(`SchedulingJobs.bucket`) as run key, so only one replica runs each occurrence. Log counts only. New notification
templates go into `NotificationTemplates` with allow-listed placeholders; `NotificationRulesTest` renders every
template through the content rules and fails the build on clinical wording.

### 16.9 Running integration tests: recreate the test database every run

`IntegrationTestBase` generates `WZ_MASTER_KEY` and `WZ_PSEUDONYM_KEY` per test JVM. Encrypted columns written by a
previous run therefore cannot be decrypted, and repeatable demo migrations re-run against old data. With
Testcontainers (default) every run gets a new container. When you use a persistent database through `WZ_TEST_DB_URL`,
**drop and recreate it before every run** (setup guide 05, Section 4.2):

```bash
psql -h localhost -U postgres -c "DROP DATABASE IF EXISTS wellnessz_test WITH (FORCE)" \
     -c "CREATE DATABASE wellnessz_test OWNER wz_migrator" -c "GRANT CONNECT, TEMPORARY ON DATABASE wellnessz_test TO wz_app"
```

The E07 demo return-after-illness requirement for `E1004` is skipped on `*_test` databases so it cannot block the E05
fitness tests – keep the `_test` suffix in the database name.

### 16.10 Running the increment-3 end-to-end test

`tools/e2e/e2e-increment3.js` drives E06, E07 and E14 through real Keycloak logins and the BFF and prints 32
`PASS`/`FAIL` lines (exit code 1 on any failure). Prerequisites as in Section 14.7: local stack, core with the `local`
profile (increment-3 demo data: absence episodes and LTA case of `E1004`, advice request of `E1001`, pre-employment of
candidate `C9001`, campaign, equipment `AUD-LHR-01/02`, fridges), BFF (with multipart parsing disabled, as shipped) and
the three dev servers. A realm imported before increment 3 lacks the four new roles and users: run
`./deploy/local/down.sh --purge` and `./deploy/local/up.sh` once.

```bash
node tools/e2e/e2e-increment3.js
```

It uses the same environment variables as the earlier scripts (`WZ_E2E_EMPLOYEE_URL`, `WZ_E2E_WORKSPACE_URL`,
`WZ_E2E_OH_URL`, `WZ_E2E_IDP_URL`, `WZ_E2E_CORE_URL`, `WZ_E2E_INTEGRATION_SECRET`, `WZ_E2E_OUT`,
`WZ_E2E_NEW_PASSWORD`) and the realm users `employee1`, `manager1`, `hr1`, `nurse1`, `physician1`, `cmo1`, `hse1`,
`reception1`, `admin1` and the increment-3 users `scheduler1`, `biomed1`, `crewlicensing1` and `candidate1`; the
integration client feeds absence episodes, IH measurements and fridge readings. The published screenshots are in
`docs/images/screens/increment-03/`. Several checks consume demo state (calibration of `AUD-LHR-02`, release of the
demo advice report, the candidate's consent), so repeat it on a freshly purged database (setup guide 05, Section 7.3).
Run all increment scripts before a release (CI/CD Guide, Section 3.1).

## 17. Worked example: E04, E08, the partner portal and the increment-4 SPIs (increment 4)

Increment 4 added two business modules that exchange money, referrals and claim context in both directions – E04 posts
costs and reads the claim context, E08 requests IMEs and listens to referral status – without a compile-time
dependency in either direction. The answer is again a set of small kernel SPIs, plus three **module SPIs** that are the
seams for vendors still to be selected (video platform, e-prescribing, insurer / authority connectors). It also added
the first front end for external users. Design background:
[Solution Design E04/E08](../increment-04/01-Solution-Design-E04-E08.md); endpoints:
[API Contract E04/E08](../increment-04/API-Contract-E04-E08.md); requirement coverage:
[Requirements Traceability Matrix](../increment-04/02-Requirements-Traceability-Matrix.md); changes:
[Release Notes](../increment-04/03-Release-Notes-Increment-4.md).

### 17.1 Module wiring

| Step | E04 (`modules/wz-e04-treatment`) | E08 (`modules/wz-e08-claims`) |
|---|---|---|
| Package | `com.wellnessz.e04` | `com.wellnessz.e08` |
| Maven dependencies | `wz-e03-record` (allergies, medication list, draft encounters, CDS), `wz-e05-fitness`, `wz-documents` | `wz-e05-fitness` (RTW status), `wz-documents` – **never** `wz-e03-record` |
| Root `pom.xml` / core app | `<module>` + `<dependencyManagement>`; dependency in `apps/wz-core-app/pom.xml` | same |
| Registration | `E04Module` (code `E04`) | `E08Module` (code `E08`) |
| Schema / migration | `treatment` (V14); platform additions in V13 | `claims` (V15) |
| Demo data (`db/demo`) | `R__demo_e04_treatment.sql` | `R__demo_e08_claims.sql` + `ClaimsDemoSeeder` (`@Profile({"local","test"})`, fills encrypted demo fields) |
| ArchUnit | `treatmentAndClaimsAreIndependent` | `claimsDoesNotDependOnTreatmentOrClinicalRecord`; both covered by `earlierIncrementsDoNotDependOnIncrement4` and `earlierModulesDoNotDependOnLaterOnes` (17 rules) |
| Jobs (`JobRunGuard`) | `treatment-variance`, `referral-chase`, `provider-scorecards` (`TreatmentJobs`) | `claims-report-deadlines` (`ClaimsJobs`, hourly run key) |
| Integration test | `Increment4TreatmentIntegrationTest` (10) | `Increment4ClaimsIntegrationTest` (10) |
| Unit tests | `TreatmentRulesTest` (11: plan maths, data set, chase rules, FHIR mapping, scorecards, invoice matching, medication checks, token window) | `ClaimRulesTest`, `CostMathTest`, `DisclosureRulesTest`, `ReportDeadlinesTest` (28) |

### 17.2 The increment-4 SPIs at a glance

| SPI | Kind | Provider (implements) | Consumer (calls) | How the consumer injects it | Behaviour when no implementation is deployed |
|---|---|---|---|---|---|
| `CaseCostRecorder` | kernel | E08 `CostService` | E04 `PreauthService`, invoice capture (via `TreatmentSpis.recordCost`) | `ObjectProvider<CaseCostRecorder>` (all beans) | Costs stay case-level in E04 only |
| `ExternalReferralGateway` | kernel | E04 `EReferralService` | E08 `LegalService` (IME / expert) | `ObjectProvider<ExternalReferralGateway>.getIfAvailable()` | Legal request records `referralStatus NOT_AVAILABLE` and raises an OH task |
| `ReferralStatusListener` | kernel | E08 `LegalService` (`supports("CLAIM_IME")`) | E04 on every referral status change (via `TreatmentSpis.referralStatusChanged`) | `ObjectProvider<ReferralStatusListener>` filtered by `supports` | Nobody is notified |
| `ClaimContextProvider` | kernel | E08 `ClaimService` | E04 pre-authorisation (payer default); case header via `GET /cases/{id}/claim-status` | `ObjectProvider<ClaimContextProvider>` | No payer default, no badge |
| `CaseTransitionGuard` (increment 1) | kernel | E04 `OutcomeClosureGuard` | kernel `CaseService` | `List<CaseTransitionGuard>` | – |
| `ChartContributor` (increment 2) | kernel | E04 `TreatmentChartContributor` | E03 `ChartService` | `List<ChartContributor>` | – |
| `AppointmentStatusListener` (increment 3) | kernel | E04 `TreatmentAppointmentListener` (`TREATMENT_FOLLOW_UP`, `TELEHEALTH_SESSION`) | E14 `StatusNotifier` | as in 16.2 | – |
| `TelehealthProvider` | module (E04) | `LinkTelehealthProviderConfig` (default) | E04 `TelehealthService` | single bean, default `@ConditionalOnMissingBean` | Link provider on `treatment.telehealth.base-url` |
| `EPrescribingGateway` | module (E04) | `EPrescribingConfig` (no-op default) | `GET /treatment/eprescribing/status` | single bean, default `@ConditionalOnMissingBean` | `enabled: false` with the decision memo |
| `ClaimsSubmissionGateway` | module (E08) | `DefaultClaimsSubmissionGateway` (`@Component`) | E08 `ReportService.submit` | single bean – replace with `@Primary` | Outbox event + content-free e-mail |

The rules of Section 16.2 apply unchanged (optional by construction, identifiers and amounts only, same transaction,
filter with `supports`, audit as `MODULE`). Two additions:

1. **Resolve lazily when modules call each other in both directions.** E04 calls E08 through `CaseCostRecorder` and
   `ClaimContextProvider`, and E08 calls E04 through `ExternalReferralGateway`. Injecting `ObjectProvider<…>` and
   resolving at call time (`TreatmentSpis`) avoids a bean cycle; never inject the other module's implementation type.
2. **Money crosses the SPI, clinical content does not.** `CostEntry` carries case, employee id, category, kind,
   amount, currency, date, provider code and a non-clinical description; `ClaimContext` carries status, payer and
   flags – no reserves or costs. A pre-authorisation justification never leaves E04.

### 17.3 Posting costs through `CaseCostRecorder`

```java
// E04 preauth (abridged): an estimate when the pre-authorisation is submitted
spis.recordCost(user.username(), new CaseCostRecorder.CostEntry(a.getCaseId(), a.getEmployeeId(),
        CaseCostRecorder.Category.MEDICAL, CaseCostRecorder.Kind.ESTIMATE, a.getEstimateAmount(), a.getCurrency(),
        support.today(), a.getProviderCode(), "Pre-authorisation " + a.getRef(), "PREAUTH", a.getId().toString()));
```

The entry is idempotent on `(sourceType, sourceRef, kind)`: posting the same pre-authorisation again updates the
entry instead of duplicating it, so retries and status changes are safe. Use `sourceType` values that name your object
(`PREAUTH`, `PROVIDER_INVOICE`); roll-ups count only `INVOICE` and `PAYMENT` – `ESTIMATE` and `RESERVE` are exposure.
Amounts are converted to `claims.base-currency` with `claims.fx-rates`; an unknown currency is `422
currency_not_supported`.

### 17.4 Raising a referral through `ExternalReferralGateway` and listening to it

```java
// E08 LegalService (abridged): an IME for a claim
ExternalReferralGateway gateway = referrals.getIfAvailable();
Optional<ExternalReferralGateway.ReferralRef> ref = gateway == null ? Optional.empty()
        : gateway.requestReferral(user.username(), new ExternalReferralGateway.ReferralRequest(c.getCaseId(),
                c.getEmployeeId(), "IME", r.getProviderCode(), "ROUTINE", "Independent medical examination requested for claim …",
                SOURCE_TYPE /* CLAIM_IME */, r.getId().toString()));
// empty → referralStatus NOT_AVAILABLE and an OH task; present → DRAFT referral that OH completes and sends

// E08 LegalService also implements the listener
@Override public boolean supports(String sourceType) { return "CLAIM_IME".equals(sourceType); }
@Override public void referralStatusChanged(UUID referralId, String sourceType, String sourceRef, Status status, UUID reportDocumentId) {
    // update the legal request; REPORT_RECEIVED carries the provider report's document id
}
```

The gateway creates a **DRAFT** – OH still has to complete the data set and select the employee's consent before it
can be sent, so a module can never push personal data to a provider on its own. `serviceCode` must exist in the E04
service catalogue (`treatment.service_catalogue`, for example `IME`, `ORTHOPAEDICS`, `PHYSIOTHERAPY`); unknown codes
return empty. Integration tests: `imeThroughExternalReferralGatewayNotifiesListeners` (E04 side) and the IME check in
`Increment4ClaimsIntegrationTest` (E08 side, DRAFT referral raised when E04 is deployed).

### 17.5 Implementing `TelehealthProvider`, `EPrescribingGateway` and `ClaimsSubmissionGateway`

```java
// A video-platform adapter (replaces the default link provider because the default is @ConditionalOnMissingBean)
@Component
class AcmeVideoTelehealthProvider implements TelehealthProvider {
    @Override public String joinUrl(String sessionRef, String mode, String token) {
        // create or look up the room for sessionRef via the vendor API (credentials from ENC(...) / file: secrets);
        // return a join URL that carries the single-use token; the platform later redeems it at
        // POST /api/v1/integration/telehealth/tokens/redeem with its INTEGRATION client
        return vendor.joinLink(sessionRef, "CHAT".equals(mode), token);
    }
}

// An authority connector (replaces DefaultClaimsSubmissionGateway, which is a plain @Component)
@Component @Primary
class OshAuthorityGateway implements ClaimsSubmissionGateway {
    private final DefaultClaimsSubmissionGateway fallback;                      // keep the outbox path for insurers
    @Override public Receipt submit(Submission s) {
        if (!"AUTHORITY".equals(s.recipientType())) return fallback.submit(s);
        String id = authorityApi.send(s.formCode(), s.reportRef(), s.documentId()); // fetch the signed PDF via the documents API
        return new Receipt(id);
    }
}
```

Rules: the adapter runs inside the caller's transaction – keep remote calls short with timeouts, or write to the
outbox and transmit asynchronously; the document travels only over the connector's secure channel (authorities) or as
a consent-checked `INSURER` share – never inside an event; outbound HTTPS needs a `networkPolicy.extraEgress.core`
rule and a firewall / proxy rule; a new third-party SDK goes through the dependency review (CI/CD Guide, Section 3.2).
An `EPrescribingGateway` implementation returns `Status(true, route, memo)` and adds its own endpoints only after the
legal route is approved (doc 01, D19).

### 17.6 External users: organisation scope and the MFA gate

* **Organisation from the token, not the request.** Partner endpoints derive the organisation from `org_scope`
  (`/PARTNERS/<CODE>` → `<CODE>`) and filter every query by it; never accept an organisation code from the client.
  Return `404` for another organisation's object (existence not disclosed).
* **Projections per audience.** Partner and adjuster views are separate record types (`/partner/**`) without case ids,
  clinical notes, reserves, hashes or the justification. Text written by external users is screened with
  `ClinicalContentException.screen(field, value)`.
* **Disclosure log on first read.** `DisclosureLogService.record(...)` with recipient type `PARTNER`, `INSURER`,
  `AUTHORITY`, `LEGAL` or `HR` and the purpose (Section 16.7); the claim RTW status and HR claim status are logged
  once per recipient and status.
* **Consent ids from the user, checked by the server.** Endpoints that share take an optional `consentId` (without
  one, the employee's latest active consent of the purpose is used – E04 `Consents.require`); the server checks that
  it is an **active** consent of **that employee** with the right purpose (`TREATMENT_SHARING_PARTNER`,
  `INSURER_SHARING`, `TELEHEALTH_CONSULTATION`) and answers `422 consent_required` otherwise. Shares use
  `DocumentService.shareAsSystem(actor, documentId, target, organisation, consentId)` with target `PARTNER` or
  `INSURER`.
* **MFA gate.** `PARTNER_PROVIDER` and `TPA_ADJUSTER` reach your `@PreAuthorize` only when the token proves MFA
  (`WellnessZJwtConverter`, `wellnessz.security.mfa-required-roles`). Locally the gate is **off** (the `local` profile
  sets `mfa-required-roles` to `${WZ_MFA_REQUIRED_ROLES:}` because the demo realm has no OTP enrolment). To rehearse it:

```bash
# core with the gate on: partner1 / adjuster1 then lose their roles unless their token proves MFA
WZ_MFA_REQUIRED_ROLES=PARTNER_PROVIDER,TPA_ADJUSTER \
  ./mvnw -q -DskipTests install && ./mvnw -pl apps/wz-core-app spring-boot:run -Dspring-boot.run.profiles=local
# to make it pass locally: Keycloak admin → Users → partner1 → Required user actions: Configure OTP; enrol an
# authenticator app at the next login; then add the AMR mapper (or a level-of-authentication flow) so the token
# carries amr=otp / acr=2 (setup guide 05, Section 6.1)
```

Add new external roles to `mfa-required-roles` (default in `WellnessZSecurityProperties` and `application.yml`) and to
`MfaRoleGateTest`.

### 17.7 The partner portal (`web/partner-portal`)

Same stack and conventions as the other web apps (React 19, TypeScript, Vite, hash routing, BFF only, no browser
storage, strict CSP, nginx-unprivileged on 8080). Specifics:

* **Access from `/me`, not from the session.** `GET /bff/api/me` decides which screens exist; without a partner role
  (for example because the token did not prove MFA) the portal shows the MFA / no-access page. Role gating is
  convenience; the core enforces access.
* **Whitelisting mappers.** `src/api/*` keeps only the contract fields; add a field there explicitly with a test.
* **Errors.** `404` → *not found or not shared with your organisation*; `422 clinical_content_not_allowed` highlights
  the field; `422 consent_required` explains the inactive consent; problem `detail` is never rendered.
* **Uploads.** PDF / JPEG / PNG ≤ 10 MB (`src/api/upload.ts`), multipart with the CSRF header; the core validates again.
* **Text.** All UI text in `src/strings.ts`.

```bash
cd web/partner-portal && npm ci
npm run dev        # http://localhost:5176 – proxies /bff /oauth2 /login /logout → $WZ_BFF_TARGET (default http://localhost:8090)
npm run lint && npm test && npm run build      # 119 tests at the increment-4 baseline
```

The BFF needs no change for the portal (same client `wellnessz-bff`); the Keycloak client lists
`http://localhost:5176/login/oauth2/code/wellnessz`, the post-logout URI and the web origin, and the core's local CORS
list includes 5176. A new host in another environment needs the same three Keycloak / Entra entries and the Helm
`partnerPortal` component (document 04, Section 20). See `web/partner-portal/README.md` for the screen inventory.

### 17.8 Running the increment-4 end-to-end test

`tools/e2e/e2e-increment4.js` drives E04 and E08 across **all four apps** through real Keycloak logins and the BFF and
prints 22 `PASS`/`FAIL` lines (exit code 1 on any failure). Prerequisites as in Section 16.10 plus the partner portal
dev server on 5176; the core runs with the `local` profile (gate off, demo data `R__demo_e04_treatment.sql`,
`R__demo_e08_claims.sql`, `ClaimsDemoSeeder`). A realm imported before increment 4 lacks the five new roles, the users
`claims1`, `adjuster1`, `legal1`, `finance1`, `procurement1`, `physio1` and the 5176 redirect: run
`./deploy/local/down.sh --purge` and `./deploy/local/up.sh` once.

```bash
node tools/e2e/e2e-increment4.js
```

Environment variables: those of the earlier scripts plus `WZ_E2E_PARTNER_URL` (default `http://localhost:5176`). Realm
users: `employee1`, `hr1`, `nurse1`, `physician1`, `cmo1`, `nursinghead1`, `dpo1`, `partner1` and the increment-4 users
`claims1`, `adjuster1`, `legal1`, `finance1`, `procurement1`, `physio1`; the integration client is used for the FHIR
and insurer endpoints. The 26 published screenshots are in `docs/images/screens/increment-04/`. Like the increment-3
script it **consumes demo state** (it opens claim `CLM-2026-000001`, exports the month's costs once, sets a litigation
hold), and the increment-3 script consumes its own; run the scripts in order 1 → 4 on a **freshly purged** database
(`./deploy/local/down.sh --purge`, `./deploy/local/up.sh`, restart the core). Run all four increment scripts before a
release (CI/CD Guide, Section 3.1).

## 18. Worked example: E09, E10, E11, E12, restricted classes and the duty-assignment feed (increment 5)

Increment 5 – the final increment – added four business modules for **restricted classes and health promotion**:
E09 mental health, psychosocial risk and peer support; E10 drug, alcohol and psychoactive substance testing; E11 travel
health, immunisation campaigns and communicable disease / exposure management; E12 health promotion, wellbeing and
prevention campaigns. The design point that matters for every developer is the **restricted class**: mental-health
and substance-programme records are `RESTRICTED_CLINICAL`, opened only by a named restricted-class clinician, while
managers, HR, rostering and safety managers see status only and every aggregate is suppressed below the minimum group
size. Design background: [Solution Design E09/E10/E11/E12](../increment-05/01-Solution-Design-E09-E10-E11-E12.md);
endpoints, roles and rules: [API Contract E09–E12](../increment-05/API-Contract-E09-E10-E11-E12.md); requirement
coverage: [Requirements Traceability Matrix](../increment-05/02-Requirements-Traceability-Matrix.md); changes and
known limitations: [Release Notes](../increment-05/03-Release-Notes-Increment-5.md).

### 18.1 Module wiring

| Step | E09 (`modules/wz-e09-mentalhealth`) | E10 (`modules/wz-e10-substance`) | E11 (`modules/wz-e11-travelhealth`) | E12 (`modules/wz-e12-promotion`) |
|---|---|---|---|---|
| Package | `com.wellnessz.e09` | `com.wellnessz.e10` | `com.wellnessz.e11` | `com.wellnessz.e12` |
| Maven dependencies (business) | `wz-e05-fitness`, `wz-documents` | `wz-e05-fitness`, `wz-documents` | `wz-e07-surveillance` (immunisation registry), `wz-documents` | `wz-e03-record`, `wz-documents` |
| Registration | `E09Module` | `E10Module` | `E11Module` | `E12Module` |
| Schema / migration | `mentalhealth` (V17) | `substance` (V18) | `travelhealth` (V19) | `promotion` (V20) |
| Platform migration | V16 – `masterdata.duty_assignment`, partners `EAP_WELLBEING`, `TOXLAB`, `UKHSA_LOCAL`, retention classes `RESTRICTED_RECORD`, `PUBLIC_HEALTH` | | | |
| Demo data (`db/demo`) | `R__demo_e09_mentalhealth.sql` + `MentalHealthDemoSeeder` | `R__demo_e10_substance.sql` + `SubstanceDemoSeeder` | `R__demo_e11_travelhealth.sql` | `R__demo_e12_promotion.sql` |
| Jobs (`JobRunGuard.tryStart`) | `cism-follow-ups`, `peer-callback-purge` | `substance-follow-ups`, `substance-windows` | `exposure-timers`, `public-health-deadlines`, `travelhealth-daily` | `screening-follow-up`, `ergonomic-actions` |
| Integration test | `Increment5MentalHealthIntegrationTest` (11) | `Increment5SubstanceIntegrationTest` (7) | `Increment5TravelHealthIntegrationTest` (14) | `Increment5PromotionIntegrationTest` (13) |

ArchUnit (`ArchitectureTest`, now **22 rules**) gained five rules, and the four packages were added to the package
lists of the platform, kernel and document-service rules:

| Rule | Meaning |
|---|---|
| `earlierIncrementsDoNotDependOnIncrement5` | E01–E08 and E14 never depend on E09–E12 |
| `restrictedProgrammesAreIndependent` | E09 / E10 never depend on E03, E04, E06, E07, E08, E11, E12 or E14 (their Maven dependency is E05 for fitness publication) – restricted programmes keep their own records |
| `restrictedProgrammesDoNotDependOnEachOther` | E10 reaches E09 **only** through the kernel SPI `SupportProgrammeReferral` |
| `travelHealthAndPromotionAreIndependent` | E11 builds on E07, E12 on E03; neither depends on E04, E06, E08, E09, E10, E14 (bookings go through `AppointmentBooking`) |
| `promotionDoesNotDependOnTravelHealth` | E12 never depends on E11 |

### 18.2 Kernel SPI: `SupportProgrammeReferral`

When the MRO verifies a positive result (or a refusal) in E10, the employee must be offered confidential support in
E09 – without E10 knowing anything about E09 and without clinical detail crossing the boundary.

```java
// platform/wz-case-kernel/.../support/SupportProgrammeReferral.java
public interface SupportProgrammeReferral {
    /** reason: SUBSTANCE_POSITIVE | SUBSTANCE_REFUSAL | CRITICAL_INCIDENT | EXPOSURE | OTHER – a code, never clinical text.
     *  @return the confidential support case id, empty when the programme module is not deployed */
    Optional<UUID> refer(String actor, String employeeId, UUID relatedCaseId, String reason, String sourceType, String sourceRef);
}
```

| | |
|---|---|
| Provider | E09 `SupportProgrammeReferralAdapter` (`com.wellnessz.e09.casework`): idempotent per `sourceType` + `sourceRef`; opens a restricted support case with source `PROGRAMME_REFERRAL` and sends the content-free `support.urgent-callback` notification to the named clinicians |
| Consumer | E10 `PathwayService` after MRO verification: `ObjectProvider<SupportProgrammeReferral>`, `orderedStream().findFirst()`, call `refer(actor, employeeId, caseId, "SUBSTANCE_POSITIVE" \| "SUBSTANCE_REFUSAL", "DA_TEST", testRef)` |
| No implementation deployed | E10 audits `DA_SUPPORT_REFERRAL` with outcome `FAILURE` ("support programme module not deployed") and carries on – removal from duty and the return-to-duty plan do not depend on E09 |
| Crossing the SPI | employee id, related case id, reason code, source type and reference – no result, substance, cut-off or note |

Follow the SPI rules of Sections 16.2 and 17.2 (optional by construction, identifiers only, resolve lazily, audit as
`MODULE`). Other future callers (an E11 exposure or a critical incident) use the same reason codes. A client module can
supply its own implementation (for example a different EAP routing); because E10 takes the first bean in order, it
must declare `@Order(Ordered.HIGHEST_PRECEDENCE)` and be proven by an integration test (Client Customisation Guide,
section 7.2).

### 18.3 Platform: the duty-assignment feed (`masterdata.duty_assignment`)

Crew rosters, shifts and facility access are needed by three increment-5 features: E09 critical-incident support
(affected crew from the flight roster), E11 contact tracing (shared duties and proximity groups) and E11 travel
advisories (crew rostered to a destination). They share one platform table instead of three module copies.

| Aspect | Detail |
|---|---|
| Table (V16) | `masterdata.duty_assignment`: `source` (`CREW_ROSTER` \| `SHIFT` \| `FACILITY`), `source_ref` (unique per source – upsert key), `duty_ref` (flight / shift / facility), `duty_date`, `starts_at`, `ends_at`, `employee_id`, optional `duty_role`, `location_code`, `destination_code`, `proximity_group`; indexes on employee, duty, proximity group and destination |
| Ingest | `POST /api/v1/integration/duty-assignments` – role `INTEGRATION`, **internal machine clients only**: a client bound to a partner organisation (`CurrentUser.partnerOrganisation() != null`) is refused. Body `{source, duties:[{sourceRef, dutyRef, dutyDate, startsAt, endsAt, employeeRef (id or badge), …}]}` → `{received, upserted, unmatched[]}` |
| Read | `GET /api/v1/masterdata/duty-assignments?employeeId=&from=&to=` – OH roles, `SAFETY_MANAGER`, `CREW_SCHEDULING`; window ≤ 62 days |
| Module access | Inject `DutyAssignmentRepository` (platform, so allowed by ArchUnit): `findForEmployee(employeeId, from, to)`, `findOnDuties(dutyRefs, from, to)` (co-workers on the same flight / shift), `findInProximityGroups(groups, from, to)` |
| Rules | Duties are operational data, not clinical data, but combined with a case they reveal who is affected: never return a co-worker list to non-OH roles; contact notices are content-free (`tracing.contact-notice`, no condition, no index case); the safety manager sees counts only |

### 18.4 Coding rules for restricted classes

These rules are enforced by code review, the integration tests and – where possible – ArchUnit. They apply to E09 and
E10 today and to any future module or client module that handles `RESTRICTED_CLINICAL` data.

| # | Rule | How it is done in increment 5 |
|---|---|---|
| R1 | **Classify** restricted records as `DataClassification.RESTRICTED_CLINICAL` and keep them on restricted kernel cases (`PSYCHOLOGICAL` / `MENTAL_HEALTH`, `SUBSTANCE`) in their own schema | E09 support cases, screening, call-backs, EAP clinical detail; E10 results |
| R2 | **Named clinician only.** Check the restricted role **and** the named list in the service, not only with `@PreAuthorize`: E09 content for `PSYCH_CLINICIAN` users in `mentalhealth.named-clinicians` (empty = every `PSYCH_CLINICIAN`); E10 results for `MRO` (and `OH_PHYSICIAN` listed in `substance.acting-mro-physicians`) | Everybody else – including a general OH nurse or physician – gets **`404`** for a restricted record (a `403` would confirm that it exists) and an audited `DENIED`; role-gated functions without record context may answer `403` (for example the MRO queue for the programme manager) |
| R3 | **Placeholder counts only** outside the restricted team | `GET /support/placeholder?employeeId=` → `{restrictedCases}`; the OH chart shows "1 restricted item – hidden", nothing on zero |
| R4 | **Break-glass** is the only override for other clinicians; it is time-limited, logged and reviewed by the DPO (increment 2, `BreakGlassService`) | ABAC `DefaultPolicies` |
| R5 | **Status only** for managers, HR, rostering and safety managers: duty status (available / removed from safety-sensitive duty / return pending), counts – never scores, conditions, test reasons, results or counselling content | `GET /workspace/testing/status` (fields `employeeId`, `displayName`, `dutyStatus`, `since`); fitness publication through E05 |
| R6 | **Employee-controlled sharing** for E09: `NOTHING` \| `FITNESS_ONLY` \| `FITNESS_AND_ADJUSTMENTS`, revocable; check it at read time, not at write time | `GET`/`PUT /me/support/sharing`, `GET /workspace/psychosocial/shared-outcomes` |
| R7 | **Pseudonymity** where the requirement demands it: peer-support requests travel as alias + contact code; the contact log holds no employee identity; call-back phone numbers are purged after 30 days | `peer-callback-purge` job |
| R8 | **Disclosure log** for every status or document that crosses the restricted boundary (HR, crew scheduling, manager) | `DisclosureLogService` in `PathwayService` |
| R9 | **Restricted documents** are generated with `DocumentService.generateSigned(actor, template, employeeId, caseId, fields, DataClassification.RESTRICTED_CLINICAL)`; templates `AUTHORITY_FITNESS_REPORT`, `CHAIN_OF_CUSTODY_FORM` | Known limitation: the MRO opens results and the chain-of-custody form through the E10 MRO endpoints; opening `RESTRICTED_CLINICAL` documents through the generic `/documents` API is recognised for `PSYCH_CLINICIAN` only (follow-up item in the release notes) |
| R10 | **Content-free notifications.** Templates (`support.*`, `cism.*`, `testing.*`, `tracing.*`, …) must pass `ContentRules`, which reject clinical text and words such as *positive*, *negative*, *result*, *drug*, *alcohol* | `NotificationRulesTest` renders every template |
| R11 | **Small-cell suppression** on every aggregate (psychosocial register, testing statistics, campaign uptake, insights, advisory acknowledgement counts): `wellnessz.analytics.min-group-size` (default 5) via `SmallCellSuppression` / the module's `minGroupSize()`; suppressed cells carry no numbers | integration tests assert suppression |
| R12 | **Bound machine clients.** External systems may only touch their own organisation's records: laboratory results only from the client bound to `TOXLAB` (unbound or other client → `404`); the EAP partner sees minimal referral data for its own organisation and returns attendance only – free text is refused with `422 field_not_allowed` | `CurrentUser.partnerOrganisation()` checks in the services |
| R13 | **Consent-gated data** (wearables): only consented enrolments, daily totals only; consent withdrawal stops processing (`ConsentWithdrawalListener`) | E12 `WearableConsentListener` |
| R14 | **Never** include restricted-class data in analytics, exports, events to enterprise destinations, logs, problem details or audit reasons; events carry identifiers and status codes only | E10 `DutyStatusChanged(employeeId, dutyStatus, since)` |
| R15 | **Clinical content is configuration** that needs sign-off: screening instruments (PHQ-9, GAD-7) and cut-offs, testing policy cut-offs, travel recommendations, PEP timers | reference data, Clinical Governance / MRO / Legal sign-off before go-live |

Every restricted endpoint needs negative integration tests: a general OH user gets `404` and sees only the
placeholder, a manager sees status only, an unbound machine client gets `404`, and suppressed aggregates carry no
numbers below the minimum group size.

### 18.5 BFF and web apps

* BFF: the workspace allow-list in `apps/wz-bff/src/main/java/com/wellnessz/bff/ApiProxyController.java` gained
  `/workspace/testing`, `/workspace/psychosocial`, `/workspace/campaigns` and `/workspace/ergonomics`; the workspace
  roles gained `SAFETY_MANAGER` and `CREW_SCHEDULING`. Restricted `/support/**` and MRO paths go through the normal
  proxy – the core decides, the BFF never filters clinical content itself.
* OH workspace: support cases, urgent call-backs, the restricted chart placeholder, peer-support volunteer and
  coordinator pages, CISM, psychosocial register, mental-health fitness, testing (selections, policies, collection,
  MRO, reports), contact tracing, exposure timers, travel consultations, advisories, public-health reports,
  vaccination campaigns, promotion insights, campaign planner, screening events, ergonomics and campaign reports. Role
  routing in `roles.ts`: general OH roles never see support content.
* Employee app: Support (signposting and emergency banner), Peer support, Travel health, Campaigns, Workplace checks
  (testing notices – only where and when to report).
* Manager workspace: Psychosocial risk, Drug & alcohol testing (HR duty status), For-cause request, Ergonomics, Unit
  campaigns. Mappers whitelist fields (for example for-cause requests show "With the testing programme" after
  collection; observations and results are dropped).
* Partner portal: EAP referrals for `PARTNER_PROVIDER` users of `/PARTNERS/EAP_WELLBEING`.

### 18.6 Outbound destinations and gateways

New allow-listed destinations: `campaign-app` (E12 campaign publication) and `sms-safety` (urgent call-back and tracing
contact). Real external connectors are still simulated or defaulted – EAP provider system, toxicology laboratory LIMS
(bound machine client and API ready), campaign app, wearable vendor APIs, public-health authority submission (outbox +
e-mail by default) and the SMS gateway. A client that brings its own gateway implements the relevant SPI in a client
module (Client Customisation Guide, section 14 shows an SMS gateway end to end).

### 18.7 Configuration keys introduced or used

| Key | Default | Purpose |
|---|---|---|
| `mentalhealth.named-clinicians` (E18 `ConfigService`, seeded by V17) | `psych1` – the demo user, seeded in **every** environment | Named restricted-class clinicians for E09 (usernames, comma separated); empty = every `PSYCH_CLINICIAN`. Set it per client in the configuration screens before go-live |
| `substance.acting-mro-physicians` (E18 `ConfigService`) | empty | `OH_PHYSICIAN` users allowed to act as MRO |
| `wellnessz.analytics.min-group-size` (`WZ_ANALYTICS_MIN_GROUP`) | 5 | Small-cell suppression for every aggregate – never lower it |
| `WZ_MFA_REQUIRED_ROLES` | `PARTNER_PROVIDER,TPA_ADJUSTER` | MFA gate for external roles (EAP users included) |
| `WZ_FEATURE_*` | none | Client feature flags from the Helm `customisation.featureFlags` (plumbing only – no product feature reads them yet) |

### 18.8 Future enhancements and client-specific features

* A new module or a new feature in an existing module follows the CI/CD Guide checklist (section 10.5): Maven module,
  next Flyway version (`V21` after increment 5), ArchUnit rule, BFF resource, web app screens, E2E script, docs and
  OpenAPI regeneration.
* A seam that other modules or clients need becomes a kernel or module SPI with a default that keeps today's
  behaviour – the pattern of `SupportProgrammeReferral`, `ExternalReferralGateway` and `ClaimsSubmissionGateway`.
* A feature that only one client needs is built as described in the
  [Client Customisation Guide](../customisation/Client-Customisation-Guide.md): configuration, reference data,
  feature flags, client SQL migrations in `client_<client>`, or an SPI implementation in a client module of a client
  build. Mounting extension JARs into the product image is not implemented.

### 18.9 Running the increment-5 end-to-end test

`tools/e2e/e2e-increment5.js` drives E09–E12 across all four apps through real Keycloak logins and the BFF and prints
24 `PASS`/`FAIL` lines (exit code 1 on any failure). Prerequisites as in Section 17.8; a realm imported before
increment 5 lacks the eight new roles and the users `peer1`, `peercoord1`, `safety1`, `damanager1`, `mro1`,
`collector1`, `promo1`, `ergo1`, `eap1`: run `./deploy/local/down.sh --purge` and `./deploy/local/up.sh` once.

```bash
node tools/e2e/e2e-increment5.js
```

The 35 published screenshots are in `docs/images/screens/increment-05/` (for example
`inc5-oh-03-chart-restricted-placeholder.png`, `inc5-oh-12-testing-mro.png`, `inc5-mgr-02-hr-duty-status.png`,
`inc5-partner-01-eap-referrals.png`). Like the earlier scripts it consumes demo state; run all five increment scripts
in order 1 → 5 on a freshly purged database before a release (121 checks – CI/CD Guide, section 3.1).
