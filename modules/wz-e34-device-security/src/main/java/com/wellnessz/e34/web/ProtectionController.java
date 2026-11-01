package com.wellnessz.e34.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.device.DevicePackageSignatures;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.secrets.SecretScanner;
import com.wellnessz.e34.secrets.SecretStoreService;
import com.wellnessz.e34.signing.Canonicaliser;
import com.wellnessz.e34.signing.PackageSignatureService;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.e34.support.TokenHasher;
import com.wellnessz.e34.zoning.ZoneService;
import com.wellnessz.security.identity.CurrentUserProvider;

import tools.jackson.databind.JsonNode;

/**
 * Protective controls: zones and conduits (contract §15.4), package-signing keys and signatures (§15.5), connector secrets –
 * metadata only, values are write-only (§15.6) – and the secret scanner.
 */
@RestController
@RequestMapping("/api/v1/device-security")
public class ProtectionController {

    private static final int MAX_MANIFEST = 2_000_000;

    public record CidrRequest(@NotNull @Pattern(regexp = "\\*|[A-Z0-9]{2,10}") String siteCode, @NotNull @Pattern(regexp = "[A-Z_]{2,30}") String zone,
                              @NotNull @Size(max = 64) String cidr) {
    }

    public record KeyRequest(@Pattern(regexp = "Ed25519|SHA256withECDSA") String algorithm, @Pattern(regexp = "[A-Z0-9_]{2,30}") String tenantCode) {
    }

    public record SignatureMember(String keyId, String algorithm, String value) {
    }

    public record SignResult(SignatureMember signature, String canonicalSha256) {
    }

    public record VerifyResult(boolean valid, String keyId, String algorithm, String failureCode, String canonicalSha256,
                               List<SecretScanner.Finding> secretFindings) {
    }

    public record SecretRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9._:\\-]{1,80}") String scope,
                                @NotNull @Pattern(regexp = "[A-Za-z0-9._:\\- ]{1,120}") String name, @NotBlank @Size(max = 8192) String value,
                                @Size(max = 50) List<@Pattern(regexp = Spel.ENDPOINT_ID) String> boundInstances) {

        @Override
        public String toString() {
            return "SecretRequest[scope=" + scope + "]"; // never print the value
        }
    }

    public record RotateSecretRequest(@NotBlank @Size(max = 8192) String value) {

        @Override
        public String toString() {
            return "RotateSecretRequest[]";
        }
    }

    private final ZoneService zones;
    private final PackageSignatureService signatures;
    private final SecretStoreService secrets;
    private final E34Settings settings;
    private final CurrentUserProvider users;

    public ProtectionController(ZoneService zones, PackageSignatureService signatures, SecretStoreService secrets, E34Settings settings,
                                CurrentUserProvider users) {
        this.zones = zones;
        this.signatures = signatures;
        this.secrets = secrets;
        this.settings = settings;
        this.users = users;
    }

    // ---------- zones and conduits

    @GetMapping("/zones")
    @PreAuthorize(Spel.SECOPS)
    public List<ZoneService.Zone> zones() {
        return zones.zones();
    }

    @GetMapping("/conduits")
    @PreAuthorize(Spel.SECOPS)
    public List<ZoneService.Conduit> conduits() {
        return zones.conduits();
    }

    @GetMapping("/conduits/export")
    @PreAuthorize(Spel.SECOPS)
    public ResponseEntity<?> export(@RequestParam(defaultValue = "csv") @Pattern(regexp = "csv|json") String format) {
        String actor = users.require().username();
        if ("json".equals(format)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(zones.exportJson(actor));
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("device-conduits-firewall-template.csv").build().toString())
                .body(zones.exportCsv(actor));
    }

    @GetMapping("/zone-cidrs")
    @PreAuthorize(Spel.SECOPS)
    public List<ZoneService.ZoneCidr> cidrs() {
        return zones.cidrs();
    }

    @PostMapping("/zone-cidrs")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.CREATED)
    public ZoneService.ZoneCidr addCidr(@Valid @RequestBody CidrRequest r) {
        return zones.addCidr(users.require().username(), r.siteCode(), r.zone(), r.cidr());
    }

    @DeleteMapping("/zone-cidrs/{id}")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCidr(@PathVariable UUID id) {
        zones.removeCidr(users.require().username(), id);
    }

    // ---------- package-signing keys and signatures

    @GetMapping("/signing-keys")
    @PreAuthorize(Spel.KEYS_READ)
    public List<PackageSignatureService.KeyView> keys() {
        return signatures.keys();
    }

    @PostMapping("/signing-keys")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.CREATED)
    public PackageSignatureService.KeyView rotateKey(@Valid @RequestBody(required = false) KeyRequest r) {
        return signatures.rotate(users.require().username(), r == null ? null : r.algorithm(), r == null ? null : r.tenantCode());
    }

    @PostMapping("/signing-keys/{keyId}/revoke")
    @PreAuthorize(Spel.SECOPS)
    public PackageSignatureService.KeyView revokeKey(@PathVariable @Pattern(regexp = "[A-Za-z0-9._\\-]{1,40}") String keyId) {
        return signatures.revoke(users.require().username(), keyId);
    }

    /** Signs a driver-package manifest (raw JSON) after the secret scan; returns the {@code signature} member to add. */
    @PostMapping(path = "/packages/sign", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Spel.SIGNERS)
    public SignResult sign(@RequestBody String manifestJson,
                           @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{2,30}") String tenantCode) {
        JsonNode manifest = parse(manifestJson);
        List<SecretScanner.Finding> findings = SecretScanner.scan(manifest);
        if (!findings.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "secrets_in_package",
                    "The package contains literal secrets (" + findings.size() + " findings) – use devsec: / vault: references");
        }
        byte[] canonical = Canonicaliser.canonical(manifest);
        DevicePackageSignatures.Signature s = signatures.sign(users.require().username(), tenantCode == null ? "GROUP" : tenantCode, canonical);
        return new SignResult(new SignatureMember(s.keyId(), s.algorithm(), s.signatureBase64()), TokenHasher.sha256Hex(canonical));
    }

    /** Verifies a signed manifest (its {@code signature} member) – unsigned or tampered packages are reported invalid. */
    @PostMapping(path = "/packages/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Spel.VERIFIERS)
    public VerifyResult verify(@RequestBody String manifestJson,
                               @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{2,30}") String tenantCode) {
        settings.requireEnabled();
        JsonNode manifest = parse(manifestJson);
        byte[] canonical = Canonicaliser.canonical(manifest);
        String sha = TokenHasher.sha256Hex(canonical);
        List<SecretScanner.Finding> findings = SecretScanner.scan(manifest);
        JsonNode sig = manifest.get("signature");
        if (sig == null || !sig.isObject() || sig.get("keyId") == null || sig.get("value") == null) {
            return new VerifyResult(false, null, null, "signature_missing", sha, findings);
        }
        String keyId = sig.get("keyId").asString();
        DevicePackageSignatures.Verification v = signatures.verify(canonical, sig.get("value").asString(), keyId, tenantCode == null ? "GROUP" : tenantCode);
        String declared = sig.get("algorithm") == null ? null : sig.get("algorithm").asString();
        if (v.valid() && declared != null && !declared.equals(v.algorithm())) {
            return new VerifyResult(false, keyId, v.algorithm(), "algorithm_not_allowed", sha, findings);
        }
        return new VerifyResult(v.valid() && findings.isEmpty(), v.keyId(), v.algorithm(),
                v.valid() && !findings.isEmpty() ? "secrets_in_package" : v.failureCode(), sha, findings);
    }

    @PostMapping(path = "/secret-scan", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Spel.VERIFIERS)
    public List<SecretScanner.Finding> scan(@RequestBody String json) {
        settings.requireEnabled();
        return SecretScanner.scan(parse(json));
    }

    // ---------- connector secrets (metadata only)

    @GetMapping("/secrets")
    @PreAuthorize(Spel.SECOPS)
    public ResponseEntity<List<SecretStoreService.SecretMetadata>> secrets(@RequestParam(required = false)
                                                                           @Pattern(regexp = "[A-Za-z0-9._:\\-]{1,80}") String scope) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(secrets.list(users.require().username(), scope));
    }

    @PostMapping("/secrets")
    @PreAuthorize(Spel.SECOPS)
    public ResponseEntity<SecretStoreService.SecretMetadata> storeSecret(@Valid @RequestBody SecretRequest r) {
        SecretStoreService.SecretMetadata m = secrets.store(users.require().username(), r.scope(), r.name(), r.value().toCharArray(),
                r.boundInstances());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(m);
    }

    @PostMapping("/secrets/{reference}/rotate")
    @PreAuthorize(Spel.SECOPS)
    public ResponseEntity<SecretStoreService.SecretMetadata> rotateSecret(@PathVariable @Pattern(regexp = "devsec:[0-9a-f\\-]{36}") String reference,
                                                                          @Valid @RequestBody RotateSecretRequest r) {
        secrets.rotate(users.require().username(), reference, r.value().toCharArray());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(secrets.metadata(reference).orElseThrow(() -> ApiException.notFound("Secret")));
    }

    @DeleteMapping("/secrets/{reference}")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void destroySecret(@PathVariable @Pattern(regexp = "devsec:[0-9a-f\\-]{36}") String reference) {
        secrets.destroy(users.require().username(), reference);
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_MANIFEST) {
            throw ApiException.badRequest("validation_failed", "A JSON document of at most 2 MB is required");
        }
        try {
            JsonNode n = Canonicaliser.parse(json);
            if (n == null || !n.isObject()) {
                throw ApiException.badRequest("validation_failed", "A JSON object is required");
            }
            return n;
        } catch (RuntimeException e) {
            if (e instanceof ApiException a) {
                throw a;
            }
            throw ApiException.badRequest("validation_failed", "Malformed JSON");
        }
    }
}
