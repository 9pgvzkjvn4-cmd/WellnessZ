package com.wellnessz.e34.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.e34.identity.EnrolmentService;

/**
 * Machine endpoints of E34 (contract §15.2): {@code POST /device-enrolment/enrol} (unauthenticated at the filter – this
 * controller verifies the one-time token, certificate chain, key usage and proof of possession) and
 * {@code POST /device-ingest/certificate-renewal} (authenticated endpoint, old certificate + token).
 */
@RestController
public class EnrolmentController {

    public record Platform(String os, String osVersion, String agentVersion) {
    }

    public record Hardening(String baseline, String status) {
    }

    public record EnrolRequest(String enrolmentToken, String endpointKind, String certificatePem, String proof, String hostname, Platform platform,
                               Hardening hardening, String attestation) {

        @Override
        public String toString() {
            return "EnrolRequest[kind=" + endpointKind + "]"; // never print the token
        }
    }

    public record RenewalRequest(String certificatePem, String proof) {
    }

    private final EnrolmentService enrolment;
    private final String certificateHeader;

    public EnrolmentController(EnrolmentService enrolment,
                               @Value("${wellnessz.device.ingest.certificate-header:X-Client-Cert-Sha256}") String certificateHeader) {
        this.enrolment = enrolment;
        this.certificateHeader = certificateHeader;
    }

    @PostMapping(path = "/api/v1/device-enrolment/enrol", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EnrolmentService.Enrolled> enrol(@RequestBody(required = false) EnrolRequest r, HttpServletRequest request) {
        EnrolmentService.EnrolCommand cmd = r == null ? null : new EnrolmentService.EnrolCommand(r.enrolmentToken(), r.endpointKind(),
                r.certificatePem(), r.proof(), r.hostname(), r.platform() == null ? null : r.platform().os(),
                r.platform() == null ? null : r.platform().osVersion(), r.platform() == null ? null : r.platform().agentVersion(),
                r.hardening() == null ? null : r.hardening().baseline(), r.hardening() == null ? null : r.hardening().status(), r.attestation());
        EnrolmentService.Enrolled e = enrolment.enrol(cmd, request.getHeader(certificateHeader), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(e);
    }

    @PostMapping(path = "/api/v1/device-ingest/certificate-renewal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public EnrolmentService.Renewed renew(@RequestBody(required = false) RenewalRequest r) {
        DeviceEndpointPrincipal principal = DeviceEndpointPrincipal.require();
        return enrolment.renew(principal, r == null ? null : r.certificatePem(), r == null ? null : r.proof());
    }
}
