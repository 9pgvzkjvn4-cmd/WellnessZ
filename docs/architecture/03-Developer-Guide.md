# WellnessZ OHCM – Developer Guide: Adding Modules Incrementally

| | |
|---|---|
| **Document** | 03 – Developer Guide |
| **Audience** | Developers and technical leads building business modules E01–E14 and integrations |
| **Baseline** | Repository working tree, Maven version `0.1.0-SNAPSHOT`, Java 21, Spring Boot 4.1.2 |
| **Companion documents** | 01 – Technology Architecture and Technology Selection · 02 – Security and SOC 2 Control Mapping |

This guide explains how to add a business module (an *E-number* from the logical architecture) to the base framework
without weakening its security properties. The running example is **E07 – Surveillance, statutory medicals &
immunisation**; replace names as appropriate. Everything referenced exists in the repository unless marked
*recommended*.

## 1. Orientation

### 1.1 Repository layout

| Path | Content |
|---|---|
| `pom.xml` | Parent/aggregator; Spring Boot 4.1.2 parent; managed versions (HAPI FHIR, POI, springdoc, ArchUnit …); `security` profile |
| `platform/wz-*` | Shared kernel and platform services (E15–E18, notifications), EHS intake, case kernel |
| `apps/wz-core-app` | Runnable core API; **all Flyway migrations** (`src/main/resources/db/migration`); ArchUnit and integration tests |
| `apps/wz-bff` | Backend-for-Frontend for browser apps |
| `web/employee-app`, `web/manager-workspace` | React front-ends |
| `deploy/local` | Docker Compose: PostgreSQL, Keycloak (realm import), Mailpit, GreenMail, OpenTelemetry collector |
| `config/` | SpotBugs exclusions and dependency-check suppressions (justification required) |

Where a new business module fits:

![Component view](../images/03-component-view.png)

*Figure 1 – Components of the core API. New business modules sit in the business layer next to the case kernel and
depend only on platform modules; they talk to other business modules through events.*

### 1.2 Local environment

```bash
# 1. infrastructure (PostgreSQL 5432, Keycloak 8180, Mailpit 8025/1025, GreenMail IMAP 3143, OTel)
cd deploy/local && ./up.sh

# 2. core API (8080, management 8081) – local profile enables Swagger UI and relaxed TLS to the DB
export WZ_MASTER_KEY=$(openssl rand -base64 32)        # never reuse outside your machine
mvn -pl apps/wz-core-app -am spring-boot:run -Dspring-boot.run.profiles=local

# 3. BFF (8090) and a web app (5173 / 5174)
mvn -pl apps/wz-bff -am spring-boot:run -Dspring-boot.run.profiles=local
cd web/employee-app && npm ci && npm run dev
```

Test users in the local Keycloak realm `wellnessz` (password `ChangeMe!123`, temporary): `employee1`, `manager1`,
`hr1`, `clinician1`, `psych1`, `auditor1`, `admin1`.

### 1.3 The rules you must not break

1. **Clinical data stays in the OH domain.** Anything a manager, HR or an enterprise system sees is a status-only
   projection or an aggregate with small-cell suppression.
2. **Every read of OH data goes through `PolicyEngine`** and returns a projection chosen by the granted disclosure level.
3. **Business modules talk to each other through events**, never through each other's classes or tables.
4. **No PHI in logs, exception messages, problem details, audit reasons, notification texts or event payloads to
   non-OH destinations.**
5. **Clinical free text is stored with `EncryptedStringConverter`.**
6. **Schema changes only via Flyway**, with explicit least-privilege grants to `${app_role}`.

## 2. Creating the Maven module

### 2.1 Location and naming

Create business modules under a new top-level folder `modules/` (recommended, keeps `platform/` for shared services):

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
    <!-- platform services only; never another business module -->
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
PLATFORM_ADMIN, audited). **The framework does not enforce the flag automatically** – every module must guard its
entry points:

```java
private void requireEnabled() {
    if (!modules.isEnabled(SurveillanceModule.CODE)) {
        throw ApiException.notFound("Resource");        // disabled modules are invisible
    }
}
```

Call it at the start of each controller method, scheduled job and event listener. Seed the default in your first
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
| Naming | `V<n>__<module>_<purpose>.sql`, e.g. `V3__e07_surveillance.sql`. Numbers are global and sequential; reserve the number in the PR title to avoid collisions. Never edit an applied migration – add a new one. |
| Schema | One schema per module (`surveillance`). Add it to `spring.flyway.schemas` in `apps/wz-core-app/src/main/resources/application.yml` **and** to `migrations.job.schemas` in `deploy/helm/wellnessz/values.yaml`. |
| Roles | Migrations run as the owner role (`WZ_DB_MIGRATOR_USERNAME`); the application connects as `${app_role}` (`WZ_DB_USERNAME`). Hibernate validates only (`ddl-auto: validate`). |
| Grants | End every migration with explicit, minimal grants to `${app_role}`. Omit `DELETE` where records are evidence; use `SELECT, INSERT` for append-only tables. |
| Types | `uuid` primary keys (generated in Java), `timestamptz` for instants, `date` for calendar dates, `varchar(n)` with explicit limits, `text` for encrypted columns (ciphertext is longer than plaintext). |
| Comments | Mark encrypted columns with `-- encrypted (field level)` and the classification. |
| Restricted class | Put restricted-class data (E09, E10) in its own schema so that DB-level grants and backups can be treated separately. |

### 4.2 Example migration

```sql
-- V3 – E07 surveillance programmes and results (classification: CLINICAL)
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
* New roles: add the constant to `com.wellnessz.common.model.Roles`, the IdP app role / group mapping, and the
  workspace/BFF role lists if the role uses the workspace.
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
* **Flat records** of simple values – the outbound guard inspects top-level fields only.
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
change and is delivered at least once by `OutboxRelay`.

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
  decoupled. `IncidentListener` in the case kernel is the reference implementation.

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
        requireEnabled();
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
`postgres:16-alpine` with Testcontainers (Docker required). The `test` profile supplies throw-away keys.

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

### 9.3 ArchUnit rules for a new module

Extend `apps/wz-core-app/src/test/java/com/wellnessz/app/ArchitectureTest.java`:

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

`mvn verify` (tests, JaCoCo, CycloneDX SBOM) and `mvn -Psecurity verify` (SpotBugs + FindSecBugs, OWASP
dependency-check failing at CVSS ≥ 7; needs `NVD_API_KEY`). Suppressions in `config/` require justification, owner and
expiry date. Web: `npm run lint && npm test && npm run build` with `--max-warnings 0`.

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

### 10.3 BFF

No change is normally needed – `/bff/api/**` proxies all `/api/v1/**` paths. If a module needs a role-gated alias,
follow `/bff/api/workspace/**`. Do not forward additional headers without a security review.

## 11. Configuration

| Mechanism | Use for | Example |
|---|---|---|
| `application.yml` + environment variables | Technical settings per environment (12-factor) | `wellnessz.surveillance.recall-cron: ${WZ_SURV_RECALL_CRON:0 30 2 * * *}` |
| `ENC(...)` / `file:` | Secrets (passwords, client secrets, API keys) | `password: ${WZ_LAB_PASSWORD:}` with `ENC(...)` value or `file:/mnt/secrets/lab-password` (property name must contain password/secret/key/token/credential for `file:` resolution) |
| E18 `ConfigService` | Business configuration changed by OH admins without deployment (flags, SLA values, JSON form/rule definitions) | `config.flag("modules.e07.enabled", false)`, `config.get("surveillance.noise.interval-months")` |
| `@ConfigurationProperties` | Typed groups of settings | see `MailboxProperties` |

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
| Restricted class | Separate schema, `RESTRICTED_CLINICAL` classification, no analytics inclusion without OH governance approval. |

## 13. Definition of Done for a module increment

| # | Criterion |
|---|---|
| 1 | `CaseModule` bean registered; `GET /api/v1/modules` shows `IMPLEMENTED`; enablement flag guarded on all entry points. |
| 2 | Data classification documented for each table/column; clinical free text encrypted; migration with least-privilege grants; schema added to Flyway and Helm migration settings. |
| 3 | `AccessPolicy` beans with unit tests; every read goes through `PolicyEngine`; projections per disclosure level; status-only record without clinical fields. |
| 4 | Audit events for reads, writes, exports and denials; no PHI in reasons. |
| 5 | Events versioned, flat, documented; consumers idempotent; outbound destinations allow-listed, catalogued in `ConnectorRegistry` and approved by the DPO. |
| 6 | API follows Section 8; OpenAPI reviewed; RFC 9457 errors. |
| 7 | Integration tests for the seven mandatory cases (Section 9.2); ArchUnit rules extended; all tests green. |
| 8 | `mvn -Psecurity verify` clean or exceptions recorded with owner and expiry; SBOM generated; web lint/tests/build green. |
| 9 | Threat model (doc 02, Section 7) extended for new flows; DPIA screening done; retention period for new data defined. |
| 10 | Operational readiness: metrics/alerts for new jobs, runbook entries (dead letters, replay), configuration documented, MDM keys documented if any. |
| 11 | Documentation updated: module status in doc 01, control mapping in doc 02 if controls change. |
| 12 | Peer review by a second developer and, for changes to security components, by the security champion. |
