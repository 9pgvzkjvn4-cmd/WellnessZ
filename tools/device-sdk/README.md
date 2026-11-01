# WellnessZ device driver SDK (command line)

Offline tooling for driver packages of the Medical Device Integration Engine (`wellnessz.device-driver/1`, contract §6;
backlog item E31-BL-011). The command line runs **the engine's own code** – `DriverParser`, `DriverValidator`, the simulator
(`DryRun`), the adapters and the golden datasets of `wz-e31-device-engine` – and the terminology of the shipped V22 migration,
so a package that passes here passes the conformance stage of the engine and of the E32 registry.

## Build and run

```sh
./mvnw -o -q -pl tools/device-sdk -am -DskipTests install     # builds target/wz-device-sdk.jar (self-contained)
tools/device-sdk/wz-device-sdk help
```

Exit codes: `0` success, `1` the package fails (invalid, conformance failure, signature invalid), `2` usage or I/O error –
suitable for a vendor's or integrator's CI.

## Commands

| Command | What it does |
|---|---|
| `scaffold --adapter <ADAPTER> --package-id <id> [--family F] [--model MD-xxx] [--out DIR]` | New package `<id>-0.1.0.json` from the engine's bundled sample of that adapter family (HL7V2, FHIR, POCT1A, FILE_CSV, FILE_XML, FILE_GDT, BLE_GATT, SERIAL, WEBHOOK_JSON, CLOUD_POLL_JSON, DICOMWEB, OCR, MANUAL): ids replaced, signature removed. Edit `protocol`, `parse`, `mappings`, `bindings` and the test vectors. |
| `validate <package.json> [--terminology V22.sql]` | Schema, semantic versions, engine range, secret scan (literal secrets refused – use `devsec:` / `vault:` references), mapping coverage (LOINC / MDC in the shipped terminology, UCUM for every quantity), gates, bindings, and the protocol rules shared with the E32 option packs (BLE pairing must be a P3 value – *Just Works* only as `JUST_WORKS_EXCEPTION`; start commands and historic queries need their capability). |
| `conformance <package.json> [--golden DIR] [--terminology V22.sql]` | Every test vector through the simulator against the golden datasets (the bundled ones, or your own under `--golden DIR`, where `golden/…` inputs resolve); prints PASS / FAIL per vector and the evidence SHA-256 exactly as `POST /device-engine/drivers/conformance` computes it. |
| `keygen --out DIR [--key-id DEV-NAME]` | Local Ed25519 **development** key pair (`dev-key.pk8`, `dev-key.pub`). Key ids start with `DEV-`; the E34 registry never trusts them. Keep the `.pk8` file out of version control. |
| `sign <package.json> --dev-key DIR/dev-key.pk8 [--key-id ID] [--out FILE]` | Signs the canonical manifest (UTF-8, keys sorted, no whitespace, without `signature`) with the development key and adds the `signature` member. |
| `sign <package.json> --server https://core --token <bearer> [--tenant CODE] [--out FILE]` | Signs through E34 (`POST /api/v1/device-security/packages/sign`): secret scan and the tenant's active signing key (per-tenant keys; group key without `--tenant`). Needs a signer role (INTEGRATION_TESTER, DEVICE_APPROVER, SECURITY_OPS). Publishing stays in the console: `POST /device-catalogue/drivers` with the conformance run, then certification by a second person. |
| `verify <package.json> --public-key DIR/dev-key.pub` | Verifies a development signature (tampering after signing is detected). |
| `diff <old.json> <new.json>` | Path-level diff of two package versions (`+` added, `-` removed, `~` changed; signature excluded) and the version check – a new version must be greater (versions are immutable in the registry). |

## Typical flow

```sh
SDK=tools/device-sdk/wz-device-sdk
$SDK scaffold --adapter HL7V2 --package-id vitals.acme.monitor-hl7 --model MD-027 --out work/
$SDK validate work/vitals.acme.monitor-hl7-0.1.0.json
$SDK conformance work/vitals.acme.monitor-hl7-0.1.0.json --golden work/golden
$SDK keygen --out ~/.wz-dev-keys --key-id DEV-ACME && $SDK sign work/vitals.acme.monitor-hl7-0.1.0.json --dev-key ~/.wz-dev-keys/dev-key.pk8
$SDK diff work/vitals.acme.monitor-hl7-0.1.0.json work/vitals.acme.monitor-hl7-0.2.0.json
```

Golden datasets are synthetic only (no real person data); every package needs the five kinds NORMAL, EDGE, MALFORMED,
TIME_SKEW and DUPLICATE before it is accepted into the product (see `modules/wz-e31-device-engine/src/main/resources/e31/golden/index.json`).
Tests: `tools/device-sdk/src/test/java/com/wellnessz/sdk/DeviceSdkTest.java`.
