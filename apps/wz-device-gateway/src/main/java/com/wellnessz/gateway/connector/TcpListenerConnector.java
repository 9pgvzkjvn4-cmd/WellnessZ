package com.wellnessz.gateway.connector;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.config.GatewayProperties.ListenerTls;
import com.wellnessz.gateway.support.TlsSupport;

/** Accept loop on a (TLS) server socket with one virtual thread per device connection. */
public abstract class TcpListenerConnector extends AbstractConnector {

    private static final Logger log = LoggerFactory.getLogger(TcpListenerConnector.class);
    private static final int IDLE_TIMEOUT_MILLIS = 15 * 60 * 1000;

    private final String bindAddress;
    private final int port;
    private final ListenerTls tls;
    private final boolean allowPlaintext;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;

    protected TcpListenerConnector(String id, String deviceInstanceId, IngestSink sink, Clock clock,
                                   String bindAddress, int port, ListenerTls tls, boolean allowPlaintext) {
        super(id, deviceInstanceId, sink, clock);
        this.bindAddress = bindAddress;
        this.port = port;
        this.tls = tls;
        this.allowPlaintext = allowPlaintext;
    }

    @Override
    public void start() throws IOException {
        server = TlsSupport.serverSocket(bindAddress, port, tls, allowPlaintext);
        running = true;
        Thread.ofVirtual().name("wz-" + type().toLowerCase(java.util.Locale.ROOT) + "-accept-" + id).start(this::acceptLoop);
        log.info("{} listener {} on {}:{} (tls={})", type(), id, bindAddress, localPort(), tls != null && tls.enabled());
    }

    public int localPort() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    public boolean tlsEnabled() {
        return tls != null && tls.enabled();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(IDLE_TIMEOUT_MILLIS);
                socket.setKeepAlive(true);
                connections.add(socket);
                Thread.ofVirtual().name("wz-" + id + "-conn").start(() -> {
                    try (socket) {
                        handle(socket);
                    } catch (IOException e) {
                        if (running) {
                            log.debug("{} connection closed: {}", id, e.getClass().getSimpleName());
                        }
                    } catch (RuntimeException e) {
                        error("connection_failed");
                        log.warn("{} connection failed: {}", id, e.getClass().getSimpleName());
                    } finally {
                        connections.remove(socket);
                    }
                });
            } catch (IOException e) {
                if (running) {
                    error("accept_failed");
                    log.warn("{} accept failed: {}", id, e.getClass().getSimpleName());
                }
            }
        }
    }

    /** Serves one connection until it closes. */
    protected abstract void handle(Socket socket) throws IOException;

    @Override
    public void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException e) {
            log.debug("listener close failed");
        }
        for (Socket s : connections) {
            try {
                s.close();
            } catch (IOException e) {
                log.debug("connection close failed");
            }
        }
    }
}
