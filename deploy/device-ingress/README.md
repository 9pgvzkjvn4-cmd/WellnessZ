# Device ingest – mTLS ingress (increment 6, E31 / E34)

Machine traffic from gateways, Device Host agents, data managers and vendor clouds reaches the core **only** through the
device ingest. The Helm chart renders it when `deviceIngest.enabled=true`
(`deploy/helm/wellnessz/templates/ingress-device.yaml`); this folder holds what the cluster needs around it.

| File | Purpose |
|---|---|
| `ingress-nginx-values.yaml` | Values for a **dedicated** ingress-nginx controller (IngressClass `nginx-device`) |
| `restrict-device-ingressclass.yaml` | ValidatingAdmissionPolicy: only the chart's device Ingresses may use `nginx-device` |

## 1. Contract with the core

| Item | Value | Core setting |
|---|---|---|
| Hosts / paths | `deviceIngest.host`: `/api/v1/device-ingest/**`, `/api/v1/device-enrolment/**`; `deviceIngest.webhooks.host`: `/api/v1/device-webhooks/**` | `DeviceIngestSecurityConfig` (kernel) |
| Client certificate | verified against the group-PKI CA bundle (`deviceIngest.mtls.caSecret`, key `ca.crt`, optional `ca.crl`), depth 2, optional subject regex | – |
| Thumbprint header | `X-Client-Cert-Sha256` = lower-case hex SHA-256 of the DER **leaf** certificate that nginx verified; set only when `$ssl_client_verify = SUCCESS` | `WZ_DEVICE_CERT_HEADER` (rendered from `deviceIngest.certificateHeader`), `WZ_DEVICE_REQUIRE_CLIENT_CERT=true` |
| Client-supplied headers | removed: the thumbprint header, `X-Client-Cert*`, `X-SSL-Client*`, `ssl-client-*`, `X-Forwarded-Client-Cert`, `Forwarded` (RFC 7239 – Spring's `ForwardedHeaderFilter` would prefer it), `X-Forwarded-Prefix`, `X-Original-Forwarded-For`, `X-Real-IP` | – |
| `X-Forwarded-For` | overwritten with the TCP peer (controller: `use-forwarded-headers=false` → `proxy_set_header X-Forwarded-For $remote_addr`; the snippet also overwrites the inbound header) | `server.forward-headers-strategy=framework` takes the **left-most** value → zone CIDR check (`zone_violation`) |
| Trust anchors, token pepper | `secrets.device.files` → `WZ_DEVICE_TRUST_ANCHORS` (PEM path), `WZ_DEVICE_TOKEN_PEPPER` (`file:` reference) via the same CSI / Vault mechanism as the other core secrets | `CertificateVerifier`, `TokenHasher` |
| Public API host | `/api/v1/device-*` denied: chart Ingress `<release>-api-device-deny` (nginx classes) or Terraform WAF rule (`azure/appgw.tf` `BlockDevicePathsOnPublicApi`, `gcp/armor.tf` priority 900) | – |

Why Lua: nginx exposes the client-certificate fingerprint only as SHA-1 (`$ssl_client_fingerprint`); the core requires
SHA-256. The chart-authored `configuration-snippet` uses `ngx.ssl.cert_pem_to_der` (lua-resty-core) and `resty.sha256` /
`resty.string` (lua-resty-string); both libraries, the headers-more module (`more_clear_input_headers`) and the absence of
any other `access_by_lua_block` in a location were checked against the **ingress-nginx v1.13.3** sources
(`images/nginx/rootfs/build.sh`, `rootfs/etc/nginx/template/nginx.tmpl`). Do not combine the device Ingresses with the
`auth-url` external-auth annotation (it uses `access_by_lua_file` in the same location).

## 2. Installation (per cluster that runs the core)

```bash
# 1. dedicated controller (internal LB in the gateway zone; platform patch see the values file)
helm upgrade --install ingress-nginx-device ingress-nginx/ingress-nginx --version <pinned 4.x> \
  -n ingress-nginx-device --create-namespace -f deploy/device-ingress/ingress-nginx-values.yaml
kubectl label ns ingress-nginx-device kubernetes.io/metadata.name=ingress-nginx-device --overwrite   # set automatically on >= 1.22

# 2. admission guard for the IngressClass (snippets are enabled on this controller)
kubectl apply -f deploy/device-ingress/restrict-device-ingressclass.yaml

# 3. group-PKI CA bundle for client verification (+ CRL, refreshed by the PKI job) and the server certificate
kubectl -n wellnessz create secret generic wellnessz-device-pki-ca --from-file=ca.crt=group-pki-issuing-ca.pem [--from-file=ca.crl=group-pki.crl]
#    server certificate for ingest.<env>.<domain> and webhooks.<env>.<domain> (cert-manager Certificate or Key Vault sync)
#    -> Secret wellnessz-device-ingest-tls

# 4. vault: wz-device-token-pepper (openssl rand -base64 32) and wz-device-trust-anchors (same CA bundle as ca.crt)
#    Terraform creates both with placeholders; the pepper placeholder is deliberately < 16 bytes so core refuses to start
#    until it is replaced. Enable deviceIngest BEFORE enrolling endpoints: switching from the master-key-derived pepper
#    to a configured one invalidates existing endpoint tokens (re-enrol or ROTATE_TOKEN).

# 5. chart: environments/<cloud>/<env>.yaml already set deviceIngest.enabled=true, hosts, caSecret and sourceRanges
```

`deviceIngest.sourceRanges` (edge allow-list) should list the gateway-zone supernets that are also configured as E34
`zone_cidr` rows; the core enforces the per-endpoint zone in any case. The LB must preserve the source address
(`externalTrafficPolicy: Local`, or PROXY protocol with `use-proxy-protocol=true` when the LB SNATs).

## 3. Security notes on the snippet exception

* Snippets stay **disabled** on the public / web controller (ingress-nginx default since v1.9). They are enabled only on
  `nginx-device`, which watches only the `wellnessz` namespace (`controller.scope`) and whose IngressClass may only be
  used by Ingresses labelled `wellnessz.io/zone=device` in that namespace (`restrict-device-ingressclass.yaml`).
  `server-snippet` is refused there.
* The snippet is a chart constant; the only interpolated value is the header name, restricted by `values.schema.json`
  to `[A-Za-z0-9-]+`. Checkov `CKV_K8S_152` / `CKV_K8S_153` are recorded as reviewed exceptions on the two Ingresses.
* Namespace RBAC: only the deployer identity (GitHub OIDC → Helm) may create or patch Ingresses in `wellnessz`.

## 4. Verification

| Check | How | Expected |
|---|---|---|
| No client certificate | `curl -sk https://ingest.<env>/api/v1/device-ingest/config` | TLS handshake refused / 400 from nginx |
| Certificate from another CA | `curl --cert other.pem --key other.key …` | 400 (`verify-client on`) |
| Forged thumbprint | valid certificate of endpoint A, token of endpoint B, `-H 'X-Client-Cert-Sha256: <thumbprint of B>'` | header replaced by A's real thumbprint → `certificate_mismatch` (401) |
| Forged source | add `-H 'X-Forwarded-For: 10.200.1.1' -H 'Forwarded: for=10.200.1.1'` from outside the zone | `zone_violation` (403) |
| Public API host | `curl https://api.<env>/api/v1/device-ingest/config` | 403 (deny Ingress / WAF) |
| Pack | `tools/device-security/02_tls_mtls_check.mjs`, `01_network_segmentation_check.mjs` via `.github/workflows/device-security-pack.yml` | PASS |

**Not verified in this repository:** none of the above has been run against a live controller (no cluster, no Docker
daemon in the build environment). Helm rendering, kubeconform, Checkov and the upstream ingress-nginx chart rendering
with `ingress-nginx-values.yaml` were verified; the runtime behaviour is to be confirmed by the first staging run of the
device security pack (E34-BL-010).

## 5. Platform differences (AGIC, GKE, OpenShift, RKE2)

| Capability needed | ingress-nginx (`nginx-device`) | Azure Application Gateway / AGIC | GKE Ingress (GCE L7 LB) | OpenShift router |
|---|---|---|---|---|
| Client-certificate verification | `auth-tls-*` annotations | SSL profile with trusted client CA (`appgw.ingress.kubernetes.io/appgw-ssl-profile`, profile created in Terraform) | Load balancer mTLS (Certificate Manager TrustConfig + ServerTlsPolicy on the target HTTPS proxy) – not managed by the GKE Ingress controller; Gateway API needed | Route `spec.tls.clientCertificate` / `clientTLS` in IngressController (cluster-wide) |
| SHA-256 thumbprint header | Lua snippet (this chart) | **Not available**: server variable `client_certificate_fingerprint` is SHA-1; only the PEM (`client_certificate`) can be forwarded | `{client_cert_sha256_fingerprint}` custom request header – documented by Google as **Base64**-encoded; the core accepts hex only | Not available (router forwards the PEM with `X-SSL-Client-Cert` when configured) |
| Strip client-supplied headers / overwrite XFF | snippet + controller config | Rewrite rule set (`appgw.ingress.kubernetes.io/rewrite-rule-set-custom-resource`) can delete headers and set XFF | Custom request headers overwrite; no generic header removal | `haproxy.router.openshift.io/set-forwarded-headers: replace` |
| Result | **supported** | Not usable for device ingest without a core change (accept the PEM header and hash it) | Not usable without a core change (accept Base64) | Not usable without a core change |

Recommendation for every platform: run the dedicated `nginx-device` controller behind an **internal L4** load balancer
(AKS internal LB, GKE internal passthrough NLB, MetalLB / F5 on-prem) and keep AGIC / GCE / the OpenShift router for the
web and API hosts. `deviceIngest.flavour=custom` lets an operator supply another controller's annotations, but the chart
cannot guarantee the header contract then. Follow-up (outside this increment, Java in `platform/`): let the kernel filter
accept a forwarded PEM (`X-Client-Cert`) or a Base64 SHA-256 so AGIC / GCE / OpenShift can terminate mTLS natively.

On OpenShift, installing ingress-nginx needs an SCC for its service account (or use RKE2's bundled ingress-nginx as a
second instance with the values above).
