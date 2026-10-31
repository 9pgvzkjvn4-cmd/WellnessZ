package com.wellnessz.e34.identity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wellnessz.e34.support.TokenHasher;

/**
 * Group-PKI checks for enrolment and certificate renewal (contract §15.2), JDK only: certificate chain against the trust
 * anchors {@code wellnessz.device.pki.trust-anchors} (inline PEM or a path to a PEM file, several certificates allowed) with
 * {@link CertPathValidator} (PKIX), extended key usage {@code clientAuth}, and proof of possession of the private key with
 * {@link Signature}. Revocation of client certificates is enforced through the endpoint status (REVOKED, effective on the next
 * request); CRL / OCSP checking stays with the ingress and the group PKI.
 */
@Component
public class CertificateVerifier {

    public static final String CLIENT_AUTH_OID = "1.3.6.1.5.5.7.3.2";
    private static final int MAX_PEM = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(CertificateVerifier.class);

    private final String trustAnchorsSetting;
    private volatile Set<TrustAnchor> cachedAnchors;
    private volatile long cachedStamp = Long.MIN_VALUE;

    public CertificateVerifier(@Value("${wellnessz.device.pki.trust-anchors:${WZ_DEVICE_TRUST_ANCHORS:}}") String trustAnchors) {
        this.trustAnchorsSetting = trustAnchors == null ? "" : trustAnchors.strip();
    }

    /** Thumbprint and metadata of the leaf certificate. */
    public record CertificateInfo(String sha256, String subject, String issuer, String serial, Instant notBefore, Instant notAfter) {
    }

    /** Failure with the contract error code ({@code enrolment_invalid}, {@code certificate_untrusted}, ...). */
    public static final class PkiException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        public PkiException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public List<X509Certificate> parseChain(String pem) throws PkiException {
        if (pem == null || pem.isBlank() || pem.length() > MAX_PEM || !pem.contains("-----BEGIN CERTIFICATE-----")) {
            throw new PkiException("enrolment_invalid");
        }
        try {
            Collection<? extends java.security.cert.Certificate> certs = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            List<X509Certificate> out = new ArrayList<>();
            for (java.security.cert.Certificate c : certs) {
                out.add((X509Certificate) c);
            }
            if (out.isEmpty() || out.size() > 5) {
                throw new PkiException("enrolment_invalid");
            }
            return out;
        } catch (CertificateException | ClassCastException e) {
            throw new PkiException("enrolment_invalid");
        }
    }

    public static CertificateInfo info(X509Certificate leaf) throws PkiException {
        try {
            return new CertificateInfo(TokenHasher.sha256Hex(leaf.getEncoded()), cap(leaf.getSubjectX500Principal().getName(), 500),
                    cap(leaf.getIssuerX500Principal().getName(), 500), leaf.getSerialNumber().toString(16),
                    leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant());
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new PkiException("enrolment_invalid");
        }
    }

    /** PKIX path validation of {@code chain} (leaf first) against the configured trust anchors at {@code at}. */
    public void validateChain(List<X509Certificate> chain, Instant at) throws PkiException {
        Set<TrustAnchor> anchors = anchors();
        if (anchors.isEmpty()) {
            throw new PkiException("certificate_untrusted"); // fail closed: no trust anchors configured
        }
        List<X509Certificate> path = new ArrayList<>();
        for (X509Certificate c : chain) {
            boolean isAnchor = anchors.stream().anyMatch(a -> a.getTrustedCert() != null && a.getTrustedCert().equals(c));
            if (!isAnchor) {
                path.add(c);
            }
        }
        if (path.isEmpty()) {
            throw new PkiException("certificate_untrusted");
        }
        try {
            CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(path);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false);
            params.setDate(Date.from(at));
            CertPathValidator.getInstance("PKIX").validate(certPath, params);
        } catch (GeneralSecurityException e) {
            throw new PkiException("certificate_untrusted");
        }
    }

    /** Extended key usage must include TLS client authentication; key usage (if present) must allow digital signatures. */
    public static void requireClientAuth(X509Certificate leaf) throws PkiException {
        try {
            List<String> eku = leaf.getExtendedKeyUsage();
            if (eku == null || !eku.contains(CLIENT_AUTH_OID)) {
                throw new PkiException("certificate_key_usage");
            }
        } catch (java.security.cert.CertificateParsingException e) {
            throw new PkiException("certificate_key_usage");
        }
        boolean[] ku = leaf.getKeyUsage();
        if (ku != null && (ku.length == 0 || !ku[0])) {
            throw new PkiException("certificate_key_usage");
        }
    }

    /** Proof of possession: {@code proofBase64} is a signature over {@code message} with the certificate's private key. */
    public static void verifyProof(PublicKey key, String message, String proofBase64) throws PkiException {
        if (proofBase64 == null || proofBase64.isBlank() || proofBase64.length() > 2048) {
            throw new PkiException("proof_invalid");
        }
        String algorithm = switch (key.getAlgorithm()) {
            case "EC" -> "SHA256withECDSA";
            case "RSA" -> "SHA256withRSA";
            case "Ed25519", "EdDSA" -> "Ed25519";
            default -> null;
        };
        if (algorithm == null) {
            throw new PkiException("proof_invalid");
        }
        try {
            Signature s = Signature.getInstance(algorithm);
            s.initVerify(key);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            if (!s.verify(Base64.getDecoder().decode(proofBase64.strip()))) {
                throw new PkiException("proof_invalid");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new PkiException("proof_invalid");
        }
    }

    Set<TrustAnchor> anchors() {
        if (trustAnchorsSetting.isEmpty()) {
            return Set.of();
        }
        boolean inline = trustAnchorsSetting.contains("-----BEGIN CERTIFICATE-----");
        long stamp = inline ? 0L : modified(trustAnchorsSetting);
        Set<TrustAnchor> current = cachedAnchors;
        if (current != null && stamp == cachedStamp) {
            return current;
        }
        Set<TrustAnchor> loaded = new HashSet<>();
        try {
            String pem = inline ? trustAnchorsSetting : Files.readString(Path.of(trustAnchorsSetting), StandardCharsets.US_ASCII);
            for (java.security.cert.Certificate c : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
                loaded.add(new TrustAnchor((X509Certificate) c, null));
            }
        } catch (IOException | CertificateException | RuntimeException e) {
            log.warn("Device PKI trust anchors could not be loaded: {}", e.getClass().getSimpleName());
            loaded.clear();
        }
        cachedAnchors = Set.copyOf(loaded);
        cachedStamp = stamp;
        return cachedAnchors;
    }

    private static long modified(String path) {
        try {
            return Files.getLastModifiedTime(Path.of(path)).toMillis();
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    private static String cap(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
