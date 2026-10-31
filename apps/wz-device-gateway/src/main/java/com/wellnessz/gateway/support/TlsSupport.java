package com.wellnessz.gateway.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.wellnessz.gateway.config.GatewayProperties.ListenerTls;

/**
 * Server sockets for local listeners. TLS 1.2/1.3 only; plaintext is refused unless the listener is bound to the
 * loopback interface or the documented segmented-VLAN exception ({@code wz.gateway.allow-plaintext-listeners}) is set
 * (contract §16.1, §16.4).
 */
public final class TlsSupport {

    private TlsSupport() {
    }

    @SuppressFBWarnings(value = "UNENCRYPTED_SERVER_SOCKET",
            justification = "Plaintext is reachable only for loopback binds or under the documented segmented-VLAN "
                    + "exception flag (contract 16.1/16.4); it is reported as a hardening finding in every heartbeat.")
    public static ServerSocket serverSocket(String bindAddress, int port, ListenerTls tls, boolean allowPlaintext)
            throws IOException {
        InetAddress addr = InetAddress.getByName(bindAddress);
        if (tls != null && tls.enabled()) {
            SSLContext ctx = serverContext(tls);
            SSLServerSocket s = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
            s.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            s.setNeedClientAuth(tls.requireClientCertificate());
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(addr, port), 50);
            return s;
        }
        if (!allowPlaintext && !addr.isLoopbackAddress()) {
            throw new IllegalStateException("plaintext listener on " + bindAddress + ":" + port
                    + " refused: enable TLS or set wz.gateway.allow-plaintext-listeners (segmented VLAN exception)");
        }
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(addr, port), 50);
        return s;
    }

    public static boolean plaintextPermitted(String bindAddress, ListenerTls tls, boolean allowPlaintext) {
        try {
            return (tls != null && tls.enabled()) || allowPlaintext || InetAddress.getByName(bindAddress).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    public static SSLContext serverContext(ListenerTls tls) throws IOException {
        if (tls.keyStore() == null || tls.keyStore().isBlank()) {
            throw new IllegalStateException("listener TLS enabled but no key store configured");
        }
        try {
            char[] pw = tls.keyStorePassword() == null ? new char[0] : tls.keyStorePassword().toCharArray();
            KeyStore ks = load(Path.of(tls.keyStore()), tls.keyStoreType(), pw);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            javax.net.ssl.TrustManager[] tms = null;
            if (tls.trustStore() != null && !tls.trustStore().isBlank()) {
                char[] tpw = tls.trustStorePassword() == null ? new char[0] : tls.trustStorePassword().toCharArray();
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(load(Path.of(tls.trustStore()), tls.keyStoreType(), tpw));
                tms = tmf.getTrustManagers();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tms, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("listener TLS initialisation failed", e);
        }
    }

    private static KeyStore load(Path p, String type, char[] pw) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(type == null ? "PKCS12" : type);
        try (var in = java.nio.file.Files.newInputStream(p)) {
            ks.load(in, pw);
        }
        return ks;
    }
}
