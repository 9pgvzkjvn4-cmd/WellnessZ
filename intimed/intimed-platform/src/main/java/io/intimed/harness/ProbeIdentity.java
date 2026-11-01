package io.intimed.harness;

import io.intimed.common.Paths2;
import io.intimed.security.crypto.Aead;
import io.intimed.security.crypto.MasterKey;
import io.intimed.security.endpoint.EndpointService;
import io.intimed.security.pki.LocalPki;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * mTLS identities owned by the platform per tenant: the exchange-harness probe (type HARNESS – may send TEST traffic
 * with a test subject) and the demo gateway (type GATEWAY – sends LIVE traffic like any enrolled gateway). Private keys
 * are kept wrapped with a master-derived key.
 */
@Component
public class ProbeIdentity {
    private final EndpointService endpoints;
    private final Map<String, Creds> cache = new ConcurrentHashMap<>();

    public ProbeIdentity(EndpointService endpoints) { this.endpoints = endpoints; }

    public record Creds(String endpointId, X509Certificate cert, PrivateKey key, SSLContext ssl) { }

    public Creds harness(String tenant) { return get(tenant, "ep-harness", "Exchange harness probe", "HARNESS"); }

    public Creds demoGateway(String tenant) { return get(tenant, "ep-demo-gateway", "Demo clinic gateway (simulators)", "GATEWAY"); }

    public synchronized Creds get(String tenant, String id, String name, String type) {
        String k = tenant + "/" + id;
        Creds c = cache.get(k);
        if (c != null && c.cert().getNotAfter().getTime() > System.currentTimeMillis() + 86_400_000L
                && endpoints.authenticate(c.cert()).map(EndpointService.Identity::active).orElse(false)) return c;
        Path kf = Paths2.dir("pki").resolve(tenant + "-" + id + ".key.enc");
        KeyPair kp;
        try {
            if (Files.exists(kf) && c == null) {
                PrivateKey pk = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Aead.decrypt(MasterKey.aes("probe/" + k), Files.readString(kf), k)));
                kp = null;
                X509Certificate existing = null;
                Path cf = Paths2.dir("pki").resolve(tenant + "-" + id + ".crt");
                if (Files.exists(cf)) existing = LocalPki.readCert(cf);
                if (existing != null && endpoints.authenticate(existing).map(EndpointService.Identity::active).orElse(false)) {
                    c = new Creds(id, existing, pk, LocalPki.clientSslContext(LocalPki.get().root(), new X509Certificate[]{existing, LocalPki.get().tenantCa(tenant).cert()}, pk));
                    cache.put(k, c);
                    return c;
                }
            }
            kp = LocalPki.ecKeyPair();
            Paths2.writeSecret(kf, Aead.encrypt(MasterKey.aes("probe/" + k), kp.getPrivate().getEncoded(), k).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("probe identity unavailable", e);
        }
        X509Certificate cert = endpoints.enrolInternal(tenant, id, name, type, kp.getPublic());
        LocalPki.writePem(Paths2.dir("pki").resolve(tenant + "-" + id + ".crt"), cert);
        c = new Creds(id, cert, kp.getPrivate(), LocalPki.clientSslContext(LocalPki.get().root(), new X509Certificate[]{cert, LocalPki.get().tenantCa(tenant).cert()}, kp.getPrivate()));
        cache.put(k, c);
        return c;
    }

    public void invalidate() { cache.clear(); }
}
