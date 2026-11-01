# Integration guide for medical products

## Choose a pattern

| Pattern | Use when | How (local kit) |
|---|---|---|
| EP-1 Embedded capture component | your product keeps its UI and wants one-tap capture | `<script type="module" src="https://<intimed>/assets/intimed-capture.js">` and `<intimed-capture template="TPL-18" station="OH-SPIRO-1" subject="EMP-700102">`; see `/host-demo/` |
| EP-2 FHIR API & Subscriptions | standards-native consumers | OAuth2 client credentials → `GET /fhir/r4/Observation?subject=…&code=…`; `POST /fhir/r4/Subscription` (rest-hook) |
| EP-3 HL7 v2 / IHE DEV | interface engines | receive ORU^R01 rendered by the HL7V2 adapter; send ADT A01/A02/A03 and ORM/OML over MLLP-TLS :2575 with a client certificate |
| EP-4 Webhooks & events | event-driven products | `POST /api/integration/webhooks` – HMAC-SHA256 `X-IntiMed-Signature: v1=hex(hmac(secret, timestamp + "." + body))`, `X-IntiMed-Timestamp`; reject if older than 300 s |
| EP-5 Template adapter post-back | products without FHIR/HL7 | REST JSON or PDF renderings per template instance |
| EP-6 Sidecar / OEM | vendors embedding IntiMed | run the same jar beside your product; white-label deferred |
| EP-7 Gateway-only edge | outstations, home | `java -jar intimed-platform.jar sdk enrol …` then `sdk gateway …` (encrypted store-and-forward) |

## Steps per environment setting

1. **MacBook (LOCAL)** – run the platform (`--spring.profiles.active=demo` for a populated sandbox). Create an API client
   in *Integration & APIs*, import your form as a FHIR Questionnaire in *Templates*, bind a device in the wizard, run the
   exchange test and integrate against the sandbox instances and the FHIR API.
2. **Dev** – same images and APIs in the shared cluster (cloud release); partner sandbox tenants.
3. **UAT** – real devices, harness in LIVE mode, security test kit per cloud, shadow mode before clinical sign-off.
4. **Prod (HA)** – promotion only through the pipeline; four-eyes with the Platform SRE; tokens and webhooks per tenant.

## Contracts

* FHIR R4 CapabilityStatement at `/fhir/r4/metadata`; scopes `system/Observation.read|write`, `system/Device.read`,
  `system/Subscription.write`; tenant always from the token.
* Machine API `/api/v1/…` (bearer): harness runs for driver CI (`harness.run`), catalogue (`catalogue.read`), events (`events.read`).
* Device ingress envelope (mTLS :8443): `{"configId": "...", "option": "P1…P11", "payload": "<device payload>"}`.
* Driver packages: see `resources/drivers/*.json` and `mdi-sdk validate|test|sign`.
