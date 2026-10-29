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
| `modules/wz-e01-intake`, `modules/wz-e02-triage` | Business modules of increment 1 – the worked example in Section 14 |
| `web/employee-app`, `web/manager-workspace`, `web/oh-workspace` | React front-ends (the OH clinical workspace was added in increment 1) |
| `tools/e2e` | Playwright end-to-end scripts: `e2e.js` (base framework), `e2e-increment1.js` (E01/E02, Section 14.7) |
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

# 3. BFF (8090) and a web app (5173 employee / 5174 manager / 5175 OH workspace)
mvn -pl apps/wz-bff -am spring-boot:run -Dspring-boot.run.profiles=local
cd web/employee-app && npm ci && npm run dev
```

Test users in the local Keycloak realm `wellnessz` (password `ChangeMe!123`, temporary): `employee1`, `manager1`,
`hr1`, `clinician1`, `psych1`, `auditor1`, `admin1`; since increment 1 also `nurse1`, `nurse2`, `physician1`, `ame1`,
`cmo1`, `nursinghead1`, `hse1`, `crewsched1`, `hygiene1`, `reception1`, `firstaider1`. The `local` profile loads
synthetic HRIS employees and the OH staff roster from `db/demo` (never enabled in shared environments).

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

Create business modules under the top-level folder `modules/` (keeps `platform/` for shared services). Increment 1
created it with `modules/wz-e01-intake` and `modules/wz-e02-triage`; Section 14 walks through them.

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
8. Module disabled (`modules.eXX.enabled=false`) → 503 `module_disabled`; listeners skip without dead-lettering.

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

Locally: `mvn verify` (tests, JaCoCo, CycloneDX SBOM) and `mvn -Psecurity verify` (SpotBugs + FindSecBugs at effort
Max, OWASP dependency-check failing at CVSS ≥ 7; needs `NVD_API_KEY`). Web: `npm run lint && npm test && npm run build`
with `--max-warnings 0`. Suppressions in `config/` require justification, owner and expiry date.

In CI every pull request runs `.github/workflows/ci.yml` (Java build and tests with SBOM, web lint/test/build with
`npm audit` and SBOM, gitleaks, OWASP dependency-check, dependency review, Helm/Terraform/Trivy config checks) and
`codeql.yml` (Java and TypeScript). Images are built, Trivy-gated, signed and attested by `container.yml`; releases are
deployed by `cd.yml` through `deploy-env.yml`. The `java` CI job also runs SpotBugs + FindSecBugs and fails on any
finding. The increment-1 baseline is **65 Java tests**, **0 SpotBugs findings** (5 justified exclusions) and web tests
oh-workspace 151 / employee-app 81 / manager-workspace 55; keep it that way. Before a release, also run the
end-to-end script of your increment (Section 14.7).

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
inventory.

### 10.4 BFF

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
| 11 | Documentation updated: module status in doc 01, control mapping in doc 02 if controls change; an end-to-end script and screenshots for the increment (`tools/e2e/`, `docs/images/screens/increment-NN/`). |
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
