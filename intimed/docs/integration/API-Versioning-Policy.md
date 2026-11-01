# API versioning policy

* No breaking change within a major version (`/api/v1`, FHIR R4). Additive fields and endpoints only.
* Deprecations announced in release notes and the `Deprecation`/`Sunset` headers with ≥ 12 months' notice.
* Each release publishes the FHIR CapabilityStatement and the event type list (`/api/integration/event-types`).
* Partner contract tests (the harness API and the security kit scripts 04/19) are re-run on every minor release.
* Driver package format is versioned by section; registries refuse packages that fail `DriverValidator`.
