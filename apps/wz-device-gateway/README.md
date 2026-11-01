# wz-device-gateway – edge gateway and Device Host agent

Edge tier of the Medical Device Integration Engine (E31-HLR-09, E31-F09, E34-HLR-03; API contract
`docs/increment-06/API-Contract-E31-E34.md` §7, §15.2, §16). One Java 21 / Spring Boot 4.1 application in two roles:

| Role (`wz.gateway.role`) | Where it runs | Typical connectors |
|---|---|---|
| `GATEWAY` | clinic / outstation industrial PC (hardened Debian / Ubuntu Core) | HL7 v2 MLLP (P4), POCT1-A (P5), BLE relay intake (P3), DICOM drop folder (P7) |
| `DEVICE_HOST_AGENT` | vendor PC next to the device (Windows service or Linux systemd) | export folder watcher and vendor database poller (P2), serial (P1) |

**Recorded deviation.** The solution document proposes a .NET 8 worker for the Windows Device Host agent. We build one
Java 21 agent that covers both roles on Linux and Windows: one code base, one SBOM, one test suite, one hardening and
secret-handling model (contract §0). Further deviations are listed at the end of this file.

```
 devices / vendor software                      wz-device-gateway                                   core (wz-core-app)
 ─────────────────────────      ┌──────────────────────────────────────────────────────┐      ──────────────────────
 HL7 v2 over MLLP (TLS) ───────►│ MllpListener ──┐                                     │
 POCT1-A XML (TLS) ────────────►│ PoctListener ──┤                                     │
 export folder CSV/XML/GDT/PDF ►│ FileWatcher ───┤   IngestSink                        │ HTTPS + mTLS (ingress)
 vendor DB (read-only JDBC) ───►│ VendorDbPoller ┼──► EncryptedJournal ──► Forwarder ──┼──► /api/v1/device-ingest/**
 serial-over-TCP / simulation ─►│ SerialConnector┤   (AES-256-GCM, fsync,  (single /   │     (Idempotency-Key,
 BLE relay (tablet / kiosk) ───►│ BleIntakeServer┤    ≥ 48 h, 2 GB)         batch)     │      X-Gateway-Sequence)
 DICOM drop folder ────────────►│ DicomDrop ─────┘                                     │
                                │ Heartbeat · Commands · Config · Worklist · Hardening │◄── heartbeat / commands
                                │ status page + actuator on 127.0.0.1:8095 only        │
                                └──────────────────────────────────────────────────────┘
```

## Build and test

```bash
./mvnw -o -q -pl apps/wz-device-gateway -am install            # build + 71 unit / integration tests
./mvnw -o -pl apps/wz-device-gateway -Psecurity verify -DskipTests -Ddependency-check.skip=true   # SpotBugs + FindSecBugs
```

Tests (`src/test/java`): MLLP framing and ACKs, journal encryption / restart / tamper / torn-write / capacity /
retention, folder watcher (stability, de-duplication across restarts, quarantine, archive), enrolment (proof of
possession, retry / back-off, refusal), back-off maths, forwarder (outage, lost receipt, dead letter, isolation, FIFO),
serial / BLE / POCT1-A / DICOM / vendor-database connectors, and `GatewayIntegrationTest`, which runs the whole Spring
application against a mock engine (JDK `HttpServer`): enrolment → MLLP with the engine's ACK → outage with local AA →
replay through `/device-ingest/batch` with the original keys and a lost receipt → file export → heartbeat with
`ROTATE_TOKEN` and `ISOLATE` → status page, health and Prometheus metrics.

## Configuration

Spring Boot configuration (`application.yml`, `--spring.config.additional-location=…`, environment variables).
Secrets may be written as `ENC(...)` (wz-secrets, encrypted with `WZ_MASTER_KEY` / `WZ_MASTER_KEY_FILE`) or
`file:/path` and are resolved before binding. Everything below lives under `wz.gateway`.

| Property | Default | Meaning |
|---|---|---|
| `role` | `GATEWAY` | `GATEWAY` or `DEVICE_HOST_AGENT` (`WZ_GATEWAY_ROLE`) |
| `core-url` | `https://localhost:8443` | core base URL; the API prefix `/api/v1` is added (`WZ_GATEWAY_CORE_URL`) |
| `data-dir` | `./data` | identity, queue, ledgers, watermarks, stored configuration (`WZ_GATEWAY_DATA_DIR`) – must be persistent and readable only by the service account |
| `endpoint-kind` | from role | sent at enrolment (`GATEWAY`, `DEVICE_HOST_AGENT`) |
| `hostname` | OS host name | sent at enrolment |
| `allow-plaintext-listeners` | `false` | documented segmented-VLAN exception for non-loopback plaintext listeners / serial-over-TCP; reported as a hardening finding |
| `release-quarantine` | `false` | one-off operator flag: clears the queue quarantine at start (after SECURITY_OPS released the endpoint) |
| `enrolment.token` / `enrolment.token-file` | – | one-time enrolment token (`WZ_GATEWAY_ENROLMENT_TOKEN[_FILE]`); the file is deleted after a successful enrolment |
| `enrolment.max-attempts` | `0` (unlimited) | enrolment retries; `initial-backoff` `PT5S`, `max-backoff` `PT5M` |
| `tls.key-store`, `tls.key-store-password`, `tls.key-store-type`, `tls.key-alias` | –, –, `PKCS12`, first key | client certificate from the group PKI |
| `tls.trust-store`, `tls.trust-store-password` | JDK trust | trust anchors for the core ingress |
| `tls.simulate-ingress` | `false` | **development only**: the gateway sends `X-Client-Cert-Sha256` itself (no ingress in the dev stack) |
| `tls.certificate-header` | `X-Client-Cert-Sha256` | must match `wellnessz.device.ingest.certificate-header` |
| `tls.connect-timeout` / `tls.request-timeout` | `PT10S` / `PT30S` | HTTP timeouts |
| `queue.max-bytes` | `2147483648` (2 GB) | capacity (≥ 48 h at 2× peak); the core may override |
| `queue.retention-hours` | `72` | never below 48; older items are dead-lettered (`retention_expired`), never silently dropped |
| `queue.segment-bytes` | `67108864` | segment file size |
| `forwarder.poll-interval` | `PT1S` | drain cycle |
| `forwarder.batch-size` / `batch-max-bytes` | `100` / `10000000` | batch API limits |
| `forwarder.initial-backoff` / `max-backoff` | `PT1S` / `PT5M` | exponential back-off with jitter, `Retry-After` honoured |
| `forwarder.immediate-timeout` | `PT5S` | how long a listener waits for the send lock before answering from the queue |
| `heartbeat.interval-seconds` / `baseline` | `60` / `CIS-L1` | the core may override the interval |
| `mllp[n].*`, `poct[n].*`, `watchers[n].*`, `db-pollers[n].*`, `serial[n].*`, `ble.*`, `dicom[n].*` | – | connectors, see below |
| `worklist.gdt-folder`, `gdt-sender-id`, `gdt-receiver-id` | –, `WZGW`, `DEVICE` | worklist push target |

Status page: `server.address` (`WZ_GATEWAY_STATUS_ADDRESS`, default `127.0.0.1`) and `server.port`
(`WZ_GATEWAY_STATUS_PORT`, default `8095`). Keep it on loopback; a filter refuses non-loopback peers in any case.

### Key material

The root key comes from `WZ_MASTER_KEY` / `WZ_MASTER_KEY_FILE` (OS key store / TPM-sealed file provisioned by the
hardening image). Without it the gateway creates `data-dir/keys/gateway.key` on first start (POSIX `0600`, Windows
owner-only ACL). Purpose keys are derived with HKDF-SHA256 (wz-secrets `Hkdf`) – identity file, queue and stored
configuration never share a key. AES-256-GCM is wz-secrets' `AesGcmCipher` (random 96-bit IV, 128-bit tag, associated
data binding each ciphertext to its file).

## Enrolment (zero-touch)

1. The administrator creates the endpoint in the device console (`POST /device-security/endpoints` or wizard publish) and
   receives the one-time enrolment token (≤ 72 h).
2. The node obtains its client certificate from the group PKI (EST / SCEP / CSR; for local development
   `tools/device/dev-ca.sh` from E34, or a throw-away key store:
   `keytool -genkeypair -alias gw -keyalg EC -groupname secp256r1 -dname "CN=EP-LHR-GW-01" -ext EKU=clientAuth -storetype PKCS12 -keystore gw.p12`).
3. Start the gateway with `wz.gateway.enrolment.token` (or `token-file`) and `wz.gateway.tls.key-store`. Without a stored
   identity and without a token the gateway **refuses to start**.
4. The gateway calls `POST /api/v1/device-enrolment/enrol` with `{enrolmentToken, endpointKind, certificatePem, proof,
   hostname, platform{os, osVersion, agentVersion}, hardening{baseline, status}}` where `proof` is the base64 signature
   (SHA256withECDSA / SHA256withRSA / Ed25519) with the certificate's key over `"enrol:" + token + ":" + thumbprint`.
   Transient failures (network, 429, 5xx) are retried with exponential back-off; refusals (`enrolment_token_invalid`,
   `certificate_untrusted`, …) stop at once and are logged with their code.
5. The returned `endpointId` and `endpointToken` are stored only in `data-dir/identity.enc` (AES-GCM). Every later call
   carries `X-Device-Endpoint`, `Authorization: DeviceToken …`, `X-Edge-Clock`, and – through the mTLS ingress – the
   certificate thumbprint. The token is rotated by the `ROTATE_TOKEN` command (no restart).

**mTLS.** When the core URL is `https://…` the JDK `SSLContext` built from the client key store performs mutual TLS
with the ingress, which sets `X-Client-Cert-Sha256`. In development (`--spring.profiles.active=local`, plain
`http://localhost:8080`) `tls.simulate-ingress=true` makes the gateway send the thumbprint of its configured
certificate itself – the ingress simulation described in contract §7. It is reported as hardening finding
`INGRESS_SIMULATION_ENABLED`.

## Connectors

All connectors write to the encrypted queue first (fsync) and acknowledge the device only afterwards. Log lines carry
connector ids, sequence numbers and device instance ids – never message content.

### HL7 v2 MLLP listener (P4)

```yaml
wz.gateway.mllp:
  - id: mllp-vitals
    device-instance-id: DI-LHR-VITALS-01
    bind-address: 0.0.0.0
    port: 2575
    tls: { enabled: true, key-store: /etc/wellnessz/gateway/listener.p12, key-store-password: ENC(...), require-client-certificate: false }
    sending-applications: [CONNEX]     # MSH-3 allow-list (empty = any)
    max-message-bytes: 2097152
```

MLLP framing `0x0B … 0x1C 0x0D` (noise between frames ignored, oversized frames close the connection). Each message is
forwarded to `POST /device-ingest/hl7v2` (`application/hl7-v2`, `Idempotency-Key = <endpointId>:<sequence>`,
`X-Device-Instance`). **Online:** the engine's ACK (AA / AE / AR) is relayed to the device. **Offline or backlog:** the
gateway answers `AA` ("Stored for forwarding") once the encrypted queue write is fsync-ed – store-and-forward accept
semantics, to be documented per device in the certification record. Gateway-side refusals: `AR` for senders outside
the allow-list, a full queue or a non-HL7 frame; an engine refusal after which the item is dead-lettered is answered
`AE` (400) or `AR` (other 4xx). Plaintext is refused on non-loopback addresses unless `allow-plaintext-listeners`.

### POCT1-A XML forward (P5)

```yaml
wz.gateway.poct:
  - { id: poct-dm, device-instance-id: DI-LHR-POCT-DM, bind-address: 0.0.0.0, port: 2576, tls: { enabled: true, key-store: ..., key-store-password: ENC(...) } }
```

XML documents from the data manager (`OBS.R01`, `EVS.R01`, `QCN.R01`, `DST.R01`, …) are delimited by the closing tag of
their root element (the edge does not parse XML – no XXE surface) and forwarded to `/device-ingest/poct1a`. The core's
`ACK.R01` is relayed; offline the gateway answers a local `ACK.R01` with `AA` after the durable queue write.

### Folder / file watcher (P2)

```yaml
wz.gateway.watchers:
  - id: spiro-export
    device-instance-id: DI-LHR-SPIRO-01
    path: C:/ProgramData/Vendor/Export      # read-only for the service account is enough in COPY mode
    pattern: "*"                            # glob
    archive: C:/ProgramData/WellnessZ/archive/spiro
    quarantine: C:/ProgramData/WellnessZ/quarantine/spiro
    post-action: COPY                       # COPY (default, never touches vendor files) | MOVE
    stability-seconds: 10                   # size and mtime unchanged this long before reading
    gdt-charset: ISO-8859-15
    max-file-bytes: 52428800
    poll-interval: PT5S
```

Polling (works on SMB shares and with vendor software writing in place); hidden, `*.tmp` and `*.part` files and
symbolic links are ignored. Formats by extension: CSV, XML, GDT (`8000` record check, charset declared) and PDF
(`%PDF-` check). The **de-duplication key** is the SHA-256 of the content, persisted in `data-dir/watchers/<id>.ledger`
(hashes only, 180-day horizon) and used as `Idempotency-Key` – the same export is never forwarded twice, even after a
restart or under another name. Forwarded as `POST /device-ingest/files` with `X-File-Name`, `X-File-Format`,
`X-File-Sha256` and `X-Source-Path-Hash` (hash of the watched folder, never the path). Rejected files (unsupported
format, empty, too large, failed structural check) go to the quarantine folder. PDF pairing happens centrally.

### Vendor database poller (P2, Device Host agent)

```yaml
wz.gateway.db-pollers:
  - id: audio-db
    device-instance-id: DI-LHR-AUDIO-01
    url: jdbc:sqlserver://localhost:1433;databaseName=Audio;encrypt=true
    username: wz_readonly
    password: ENC(...)                     # or credential-ref: devsec:audio-db-ro (GET /device-ingest/secrets/{ref})
    query: SELECT id, subject_id, test_date, l1k, r1k FROM dbo.Audiogram WHERE id > ? ORDER BY id
    watermark-column: id
    initial-watermark: "0"
    poll-interval: PT60S
    max-rows: 500
```

The query must be a single `SELECT` / `WITH` with exactly one `?`, bound to the persisted high-water mark, ordered by
the watermark column. The connection is `setReadOnly(true)`, every transaction is rolled back, and the account should
be read-only in the vendor database as well. Each new row is queued as a one-row CSV file (`/device-ingest/files`,
format CSV, SHA-256 idempotency key); the watermark (`data-dir/dbpollers/<id>.watermark`) advances only after the
durable queue write. JDBC drivers are not bundled: put the vendor driver JAR (SQL Server, SQLite, UCanAccess, …) in the
`loader.path` directory (`LOADER_PATH`, see *Running*). A `credential-ref` is resolved on demand and kept in memory only.

### Serial connector (P1)

```yaml
wz.gateway.serial:
  - id: bac-01
    device-instance-id: DI-LHR-BAC-01
    mode: TCP                     # TCP (terminal server / ser2net raw) | FILE | STDIN
    host: 127.0.0.1
    port: 4001
    port-name: COM3               # reported in the record
    framing: LINE                 # LINE | STX_ETX | TERMINATOR
    terminator: 3                 # byte for TERMINATOR
    encoding: TEXT                # TEXT | HEX | BASE64
    reconnect-delay: PT5S
```

`SerialSource` is the hardware abstraction: serial-over-TCP to a terminal server (Moxa NPort, Lantronix, `ser2net` in raw
mode) and the simulation sources ship; a native RS-232 / USB-CDC source can be plugged in. Frames are queued as
`/device-ingest/serial` `{records:[{deviceInstanceId, port, receivedAt, encoding, data}]}`; parsing happens centrally.
Simulation: `mode: FILE` with `file:`, or the command-line switch `--simulate-serial=<file>` (`--simulate-serial=-`
reads stdin; `--simulate-serial-device=<instance id>` sets the instance). A TCP target other than loopback needs the
plaintext exception flag.

### BLE intake (P3)

```yaml
wz.gateway.ble:
  enabled: true
  bind-address: 0.0.0.0
  port: 8097
  relay-key: ENC(...)                         # ≥ 16 characters, sent by the relay as X-Relay-Key
  tls: { enabled: true, key-store: ..., key-store-password: ENC(...) }
  peripheral-allow-list: [BOND-0007]          # addressHash or bondId; replaced by the core's GatewayConfig when present
```

`POST /ble/records` takes the contract's BLE body (`{records:[{peripheral:{address|addressHash, name, bondId}, service,
characteristic, encoding HEX|BASE64, value, receivedAt, sessionId?, queueEntryId?, operator?}]}`, ≤ 100 records) from the
gateway's BLE helper or a kiosk / tablet relay without its own endpoint identity. A raw `peripheral.address` (MAC) is
replaced by `addressHash = SHA-256(MAC + endpoint salt)` before queuing – raw MAC addresses never leave the edge. Nurse
tablets and kiosks that are endpoints themselves post to the core directly.

### DICOM metadata forward (P7)

```yaml
wz.gateway.dicom:
  - { id: cxr-drop, device-instance-id: DI-LHR-CXR-01, path: /srv/dicom-drop, archive: /srv/dicom-archive, quarantine: /srv/dicom-quarantine }
```

The modality / PACS export writes Part 10 instances (`*.dcm`) into a drop folder owned by the gateway (moved to the archive
after queuing). Metadata comes from a `<name>.json` PS3.18 sidecar when present, otherwise from a built-in minimal header
reader (explicit / implicit VR little endian; SOP / study / series UIDs, modality, dates, device and patient
identifiers; no pixel data). The forwarder uploads the instance to `POST /device-ingest/attachments`
(`X-Attachment-Kind: DICOM`, `X-Content-Sha256`) and then posts `{"archiveId": …, "metadata": [dataset]}` to
`/device-ingest/dicomweb`. A DICOM C-STORE SCP is a later edge capability (dcm4che is not on Maven Central).

## Store-and-forward

* Append-only segment files `data-dir/queue/seg-<first sequence>.wzq`, one AES-256-GCM record per line (segment name as
  associated data), acknowledgements in `acks.wzq`, dead letters in `dead-letter/dlq.wzq`, the highest sequence in
  `state.json`. Every write is fsync-ed before the device is acknowledged.
* Sequence numbers are monotonic and never reused; `Idempotency-Key = <endpointId>:<sequence>` (SHA-256 of the content
  for files and database rows). `X-Gateway-Sequence`, `X-Captured-At` (edge receipt time) and `X-Replayed: true` are sent.
* Forwarding is FIFO; single calls while online, `POST /device-ingest/batch` (≤ 100 items / 10 MB; text bodies as
  strings, files and binary as base64) once a backlog exists. An item is removed only after a 2xx (`ACCEPTED` or
  `DUPLICATE`) – a lost receipt leads to a resend that the core answers `DUPLICATE`: **no loss, no duplication**.
* Deterministic 4xx → local dead letter (reported in the heartbeat); 408 / 429 / 5xx / network → back-off;
  401 `endpoint_isolated` / `endpoint_revoked` → forwarding stops and the queue is quarantined.
* Capacity: `queue.max-bytes` (default 2 GB, ≥ 48 h at 2× peak). A full queue refuses new messages (MLLP `AR`, POCT
  `AE`, BLE 503) so devices keep them – nothing already queued is evicted. Alerts at 70 % / 90 % come from
  `queue.capacityPct` in the heartbeat; health turns `OUT_OF_SERVICE` at 90 %.
* Retention: items older than `retention-hours` (72, never below 48) are moved to the dead-letter file.
* Recovery: a torn final line (crash during write) is truncated; a record failing GCM authentication is counted
  (`corruptRecords`) and dead-lettered as `record_unreadable`, never forwarded.

## Heartbeat, commands, configuration, clock

* `POST /device-ingest/heartbeat` every `heartbeatSeconds` with `gatewayVersion, role, configVersion, uptimeSeconds,
  ntpOffsetMillis, queue{depth, oldestCapturedAt, bytes, capacityPct, deadLetters, quarantined}, diskFreePct,
  devices[{deviceInstanceId, lastSeenAt, state}], listeners[{connector, type, port, tls}], hardening{baseline, status,
  findings}`.
* **Clock skew**: the response's `serverTime` gives an NTP-style offset (`edge midpoint − server time`), reported as
  `ntpOffsetMillis` in the next heartbeat, exposed as `wz_gateway_clock_offset_milliseconds` and on the status page;
  > 2 s is a hardening finding. Items carry the edge `capturedAt`; every call carries `X-Edge-Clock`.
* **Commands** (executed once per `commandId`, result to `/device-ingest/commands/{id}/result`):
  `RELOAD_CONFIG` (pull config), `ROTATE_TOKEN` (`payload.endpointToken`, `tokenExpiresAt` – stored encrypted, used at
  once), `ISOLATE` (quarantine the queue, stop forwarding, keep data as evidence), and the device-bound commands of
  `DeviceCommandService`, which first fetch their content from `GET /device-ingest/commands/{id}/outbound`:
  `PUSH_WORKLIST` – transport `MLLP`: each HAPI-built ORM^O01 / OML^O21 / ADT^A04 / ADT^A08 to the device's
  `mllp-outbound` target, one ACK per message in the result (`acks[{controlId, code}]`); transport `GDT`: pull
  `/device-ingest/worklist`, write GDT 6302 files to `worklist.gdt-folder`. `START_ACQUISITION` – `SERIAL`: the
  driver's start bytes to the device's serial-over-TCP connector, waiting up to 10 s for the expected reply (control
  traffic, not forwarded as a reading); `MLLP`: the session order to the outbound target. `RETRIEVE_HISTORY` –
  `HL7_QUERY`: QRY^R02 to the outbound target, ORU / ORF response frames collected until `query-idle-timeout`;
  `VENDOR_DB`: the vendor-database connector's read-only query bounded by the range (watermark untouched); results to
  `POST /device-ingest/retrievals/{id}/batch`. `RESEND_LAST` answers `FAILED not_supported_by_connector`, as does a
  start command for a connector without a transmit path (file / stdin simulation).
* **Outbound MLLP targets** (`wz.gateway.mllp-outbound[]`: `id`, `device-instance-id`, `host`, `port`,
  `tls.enabled` / `trust-store` / `key-store`, `ack-timeout` PT10S, `query-idle-timeout` PT3S, `max-query-messages` 500):
  configured at the edge per device instance – the core only names the device instance, never a host. TLS 1.2 / 1.3
  with host-name verification; plaintext only to a loopback target or under `allow-plaintext-listeners`.
* **Configuration**: `GET /device-ingest/config` on start, on `RELOAD_CONFIG` and when the heartbeat reports another
  `configVersion`. Stored encrypted (`config/current.enc`, previous kept for rollback) and applied atomically:
  heartbeat interval, queue limits, BLE peripheral allow-list, log level.

## Health, metrics, status page

* `http://127.0.0.1:8095/` – status page (identity, core reachability, queue, connectors, hardening findings; no PHI,
  no secrets, CSP `default-src 'none'`), `/status` (JSON), `/actuator/health` (`gateway` indicator, liveness /
  readiness probes), `/actuator/prometheus`.
* Metrics (PHI-free labels): `wz_gateway_messages_received_total{connector,kind}`,
  `wz_gateway_forward_items_total{outcome,kind}`, `wz_gateway_forward_failures_total{reason}`,
  `wz_gateway_forward_batches_total`, `wz_gateway_queue_depth`, `_bytes`, `_capacity_pct`, `_oldest_age_seconds`,
  `_dead_letters`, `_quarantined`, `wz_gateway_core_online`, `wz_gateway_clock_offset_milliseconds`,
  `wz_gateway_connector_received{connector,option}`, `wz_gateway_connector_errors{connector,option}`.

## Security and hardening

* Runs as a non-root service account (finding `RUNS_AS_PRIVILEGED_ACCOUNT` → status `FAIL` otherwise); management port
  on loopback; no PHI outside the encrypted queue (logs carry sequence numbers and instance ids only); BLE MACs hashed;
  file paths hashed; secrets only as `ENC()` / `file:` / `devsec:` references.
* TLS 1.2 / 1.3 for listeners; plaintext only on loopback or under `allow-plaintext-listeners` (finding
  `PLAINTEXT_LISTENER_EXCEPTION:<connector>`).
* Self-check findings in every heartbeat: `RUNS_AS_PRIVILEGED_ACCOUNT`, `KEY_FILE_PERMISSIONS`, `NO_CLIENT_CERTIFICATE`,
  `CERTIFICATE_EXPIRING` (< 30 days), `INGRESS_SIMULATION_ENABLED`, `CORE_URL_NOT_TLS`, `PLAINTEXT_LISTENER_EXCEPTION:*`,
  `CLOCK_SKEW`, `DISK_SPACE_LOW`, `MANAGEMENT_PORT_NOT_LOCALHOST`. OS controls (disk encryption, firewall, EDR, USB
  policy) are verified by the E34 CIS image pipeline.
* SpotBugs + FindSecBugs (`-Psecurity`): 0 findings. Three intentional patterns carry a justified
  `@SuppressFBWarnings`: the configured vendor `SELECT` (read-only, watermark bound as a parameter) and the two
  plaintext sockets guarded by the exception flag.

## Running

### Linux (gateway or agent)

```bash
java -jar apps/wz-device-gateway/target/wz-device-gateway.jar \
  --spring.config.additional-location=/etc/wellnessz/gateway/        # application.yml with the connectors
# with vendor JDBC drivers:
java -Dloader.path=/opt/wellnessz/gateway/drivers -cp wz-device-gateway.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --spring.config.additional-location=/etc/wellnessz/gateway/
```

systemd: `service/linux/wellnessz-gateway.service` (dedicated `wellnessz` account, `StateDirectory` 0700,
`ProtectSystem=strict`, `NoNewPrivileges`, `UMask=0077`). Put `WZ_MASTER_KEY_FILE=…` and
`WZ_GATEWAY_ENROLMENT_TOKEN_FILE=…` in `/etc/wellnessz/gateway/gateway.env`.

Container (gateway role):

```bash
docker build -f deploy/docker/Dockerfile.gateway -t wellnessz/wz-device-gateway:dev .
docker run -d --name wz-gateway -v wz-gateway-data:/var/lib/wellnessz-gateway \
  -v /etc/wellnessz/gateway:/config:ro -e SPRING_CONFIG_ADDITIONAL_LOCATION=/config/ \
  -e WZ_GATEWAY_CORE_URL=https://ingest.example.org -e WZ_GATEWAY_ENROLMENT_TOKEN_FILE=/config/enrol.token \
  -p 2575:2575 wellnessz/wz-device-gateway:dev
```

The queue volume must be persistent; the status port is not published (use `docker exec … curl` or the health check).

The image `wz-device-gateway` is built, Trivy-gated, SBOM-attested and cosign-signed by `.github/workflows/container.yml`
and promoted by digest with the other images. On a clinic k3s node use the Helm chart component `deviceGateway`
(`deploy/helm/wellnessz/values-edge-k3s.yaml` + `environments/edge/<env>.yaml`: one replica, Recreate, persistent
journal volume, exec probes against the loopback status page, device-zone NetworkPolicy). Hardened host recipe:
`deploy/edge/`.

### Windows (Device Host agent)

1. Install a Java 21 runtime (e.g. Temurin JRE) and copy `wz-device-gateway.jar` to `C:\Program Files\WellnessZ\Gateway\`
   (vendor JDBC drivers into `…\Gateway\drivers\`).
2. Configuration in `C:\ProgramData\WellnessZ\Gateway\application.yml` (`role: DEVICE_HOST_AGENT`, watchers /
   db-pollers / serial). Restrict `C:\ProgramData\WellnessZ\Gateway` to the service account and Administrators.
3. Register the service with WinSW using `service/windows/wellnessz-gateway.xml` (`wellnessz-gateway.exe install`); run it
   under a dedicated low-privilege account (gMSA) with read access to the vendor export folder / database only.
4. Start once with `WZ_GATEWAY_ENROLMENT_TOKEN_FILE` pointing to the token file; afterwards the identity is stored in
   `data\identity.enc` (local key file protected by an owner-only ACL, or `WZ_MASTER_KEY_FILE`).
5. Status: `http://127.0.0.1:8095/` on the vendor PC.

### Local development against the dev stack

```bash
java -jar apps/wz-device-gateway/target/wz-device-gateway.jar --spring.profiles.active=local \
  --wz.gateway.enrolment.token=<token from POST /device-security/endpoints> \
  --wz.gateway.tls.key-store=gw.p12 --wz.gateway.tls.key-store-password=<pw> \
  --wz.gateway.mllp[0].id=mllp-dev --wz.gateway.mllp[0].bind-address=127.0.0.1 --wz.gateway.mllp[0].port=2575 \
  --simulate-serial=/path/to/serial-capture.txt
```

The `local` profile targets `http://localhost:8080` with the ingress simulation on.

## Deviations and limitations (this increment)

| Topic | Solution document / contract | Implemented | Reason |
|---|---|---|---|
| Windows Device Host agent | .NET 8 worker | the same Java 21 application in role `DEVICE_HOST_AGENT` (WinSW service) | one code base, SBOM and test suite (contract §0) |
| Edge message bus | NATS JetStream | encrypted append-only journal | contract §0 |
| Serial / USB | native RS-232 / USB-CDC, HID keyboard wedge, USB VID/PID allow-list | serial-over-TCP and file / stdin simulation behind the `SerialSource` abstraction; VID/PID allow-list and HID wedge not implemented | no native serial library in the offline build and no hardware in the test environment |
| BLE | BlueZ / OS BLE stack on gateways | relay intake endpoint (hash of MAC, bonded allow-list); BLE radio access is left to a local helper or the tablet / kiosk | no BLE stack in the JVM |
| DICOM | C-STORE SCP, MWL | drop folder + minimal Part 10 header reader or JSON sidecar → `/attachments` + `/dicomweb` | dcm4che not on Maven Central (contract §0) |
| Worklist push | ORM / ADT over MLLP, GDT 6302, vendor import file | ORM^O01 / OML^O21 / ADT^A04 / A08 over MLLP with ACKs (round 2) and GDT 6302 files; vendor import file not built | tested against simulated devices only |
| Connector topology | `GatewayConfig.instances[]` from the core | connectors declared locally; the core's config applies heartbeat interval, queue limits, BLE allow-list and log level | instance-level hot reconfiguration of listeners follows with E32 publishing |
| Batch item bodies | "base64 for binary" | text kinds as strings; `FILE` and binary content types always base64 (keeps `X-File-Sha256` verifiable) | contract leaves the encoding rule open |
| `RESEND_LAST` | gateway command | answered `FAILED not_supported_by_connector` | no connector can re-trigger a device |
| `START_ACQUISITION` | gateway command | serial-over-TCP start bytes and MLLP orders (round 2); not for the file / stdin simulation | native serial ports not available (see Serial / USB) |
| Certificate renewal | `POST /device-ingest/certificate-renewal` | not automated: the gateway reports `CERTIFICATE_EXPIRING` 30 days ahead; the new thumbprint must be bound through E34 before the renewed key store is installed | renewal flow belongs to the E34 PKI work |
