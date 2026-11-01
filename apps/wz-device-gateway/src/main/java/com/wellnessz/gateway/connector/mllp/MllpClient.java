package com.wellnessz.gateway.connector.mllp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import com.wellnessz.gateway.config.GatewayProperties;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * MLLP client to a device that accepts orders, demographics, start-from-form orders and historic queries (E31-HLR-08,
 * E31-BL-004; contract §16.1). One connection per exchange: the message is framed ({@code VT … FS CR}), the acknowledgement
 * read within {@code ackTimeout}; for a query all response frames are collected until {@code queryIdleTimeout} without a new
 * frame (or {@code maxQueryMessages}). TLS 1.2/1.3 with host-name verification; plaintext only to loopback or under the
 * segmented-VLAN exception flag.
 */
public class MllpClient {

    /** Acknowledgement of one sent message. */
    public record Ack(String code, String controlId, String text) {
    }

    private final GatewayProperties.MllpOutbound target;
    private final boolean allowPlaintext;

    public MllpClient(GatewayProperties.MllpOutbound target, boolean allowPlaintext) {
        this.target = target;
        this.allowPlaintext = allowPlaintext;
    }

    public GatewayProperties.MllpOutbound target() {
        return target;
    }

    /** Sends one message and returns the device's acknowledgement (MSA-1, MSA-2). */
    public Ack send(byte[] er7) throws IOException {
        try (Socket s = open()) {
            s.setSoTimeout((int) target.ackTimeout().toMillis());
            OutputStream out = s.getOutputStream();
            out.write(MllpFraming.encode(er7));
            out.flush();
            byte[] ack = new MllpFraming.Reader(s.getInputStream(), target.maxMessageBytes()).next();
            if (ack == null) {
                throw new IOException("connection closed before the acknowledgement");
            }
            return parse(ack);
        }
    }

    /**
     * Sends a query and collects the response frames: the device may answer with an acknowledgement / QAK first and then one
     * frame per result message. Collection ends after {@code queryIdleTimeout} without a frame, at the frame limit, or when the
     * device closes the connection.
     */
    public List<byte[]> query(byte[] er7) throws IOException {
        List<byte[]> frames = new ArrayList<>();
        try (Socket s = open()) {
            s.setSoTimeout((int) target.ackTimeout().toMillis());
            OutputStream out = s.getOutputStream();
            out.write(MllpFraming.encode(er7));
            out.flush();
            InputStream in = s.getInputStream();
            MllpFraming.Reader reader = new MllpFraming.Reader(in, target.maxMessageBytes());
            boolean first = true;
            while (frames.size() < target.maxQueryMessages()) {
                byte[] f;
                try {
                    f = reader.next();
                } catch (SocketTimeoutException e) {
                    if (first) {
                        throw e; // nothing at all within the acknowledgement timeout
                    }
                    break;
                }
                if (f == null) {
                    break;
                }
                frames.add(f);
                if (first) {
                    first = false;
                    s.setSoTimeout((int) target.queryIdleTimeout().toMillis());
                }
            }
        }
        return frames;
    }

    /** MSA-1 / MSA-2 / MSA-3 of an acknowledgement. */
    public static Ack parse(byte[] ack) {
        String text = new String(ack, StandardCharsets.ISO_8859_1);
        for (String seg : text.replace("\r\n", "\r").replace('\n', '\r').split("\r")) {
            if (seg.startsWith("MSA") && seg.length() > 4) {
                String[] f = seg.split(java.util.regex.Pattern.quote(String.valueOf(seg.charAt(3))), -1);
                return new Ack(f.length > 1 ? f[1] : "", f.length > 2 ? f[2] : "", f.length > 3 ? f[3] : null);
            }
        }
        return new Ack("", "", "no MSA segment");
    }

    /** Message type (MSH-9.1^MSH-9.2) of a frame, e.g. {@code ORU^R01}. */
    public static String messageType(byte[] frame) {
        String text = new String(frame, StandardCharsets.ISO_8859_1);
        if (!text.startsWith("MSH") || text.length() < 8) {
            return "";
        }
        String sep = String.valueOf(text.charAt(3));
        String first = text.split("[\r\n]", 2)[0];
        String[] f = first.split(java.util.regex.Pattern.quote(sep), -1);
        if (f.length <= 8) {
            return "";
        }
        String comp = f[1].isEmpty() ? "^" : String.valueOf(f[1].charAt(0));
        String[] parts = f[8].split(java.util.regex.Pattern.quote(comp), -1);
        return parts.length > 1 ? parts[0] + "^" + parts[1] : parts[0];
    }

    @SuppressFBWarnings(value = "UNENCRYPTED_SOCKET",
            justification = "Plaintext only to a loopback target or under the documented segmented-VLAN exception flag "
                    + "(contract 16.1/16.4); TLS otherwise.")
    Socket open() throws IOException {
        InetAddress addr = InetAddress.getByName(target.host());
        if (target.tls() != null && target.tls().enabled()) {
            SSLContext ctx = clientContext(target.tls());
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
            s.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            SSLParameters params = s.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(params);
            s.connect(new InetSocketAddress(addr, target.port()), 5000);
            s.startHandshake();
            return s;
        }
        if (!allowPlaintext && !addr.isLoopbackAddress()) {
            throw new IOException("plaintext MLLP to " + target.host() + " refused: enable TLS or the segmented-VLAN exception");
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(addr, target.port()), 5000);
        return s;
    }

    static SSLContext clientContext(GatewayProperties.ClientTarget tls) throws IOException {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            if (tls.trustStore() != null) {
                tmf.init(load(tls.trustStore(), tls.trustStorePassword(), tls.trustStoreType()));
            } else {
                tmf.init((KeyStore) null);
            }
            KeyManagerFactory kmf = null;
            if (tls.keyStore() != null) {
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                char[] pw = tls.keyStorePassword() == null ? new char[0] : tls.keyStorePassword().toCharArray();
                kmf.init(load(tls.keyStore(), tls.keyStorePassword(), tls.trustStoreType()), pw);
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS client context could not be created", e);
        }
    }

    static KeyStore load(String path, String password, String type) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(type == null ? "PKCS12" : type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            ks.load(in, password == null ? new char[0] : password.toCharArray());
        }
        return ks;
    }
}
