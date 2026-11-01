# Network reference designs (zones and conduits)

Deny by default; only the conduits below are opened. Every device-facing conduit terminates on an mTLS listener.

| Setting | Zones | Conduits (source → destination : port) |
|---|---|---|
| Hospital | Medical-device zone (MDZ, per unit VLAN), clinical core, DMZ, management | MDZ gateways → engine ingress :8443 (mTLS) and MLLP-TLS :2575; DICOM router (imaging VLAN) → ingress :8443; clinicians → console :443 (WAF); engine → SIEM syslog-TLS; management → :9444 loopback/bastion only |
| Clinic / OH | Clinic device VLAN, vendor-PC VLAN (isolated, SC-07), user LAN | device host on vendor PC → ingress :8443 (outbound only); BLE/serial on the clinic gateway; no inbound to vendor PCs |
| Home / RPM | Internet | vendor cloud → webhook :443 (HMAC, replay window) or engine → vendor API (egress allow-list); consent-bound enrolment |
| Cloud | Private cluster, private endpoints, WAF | site gateways → ingress over VPN/Private Link; no public storage; KMS/Key Vault private endpoints |

The provisioning wizard (step 3) pre-fills the firewall request from the tenant's zone profile; script 01 of the security
test kit validates it against real segments in UAT (deferred for the local scope).
