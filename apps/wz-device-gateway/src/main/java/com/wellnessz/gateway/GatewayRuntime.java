package com.wellnessz.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.ConnectorRegistry;
import com.wellnessz.gateway.core.Backoff;
import com.wellnessz.gateway.identity.EndpointIdentity;
import com.wellnessz.gateway.identity.EnrolmentClient;
import com.wellnessz.gateway.identity.EnrolmentException;
import com.wellnessz.gateway.identity.IdentityStore;
import com.wellnessz.gateway.ops.ConfigService;
import com.wellnessz.gateway.queue.EncryptedJournal;

/**
 * Start-up sequence: (1) endpoint identity – stored encrypted, or obtained by zero-touch enrolment with the one-time
 * token; the gateway refuses to start without either (contract §16.4); (2) last stored configuration, then a fresh pull;
 * (3) connectors. Forwarding and heartbeats run only once the runtime is ready.
 */
public class GatewayRuntime implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GatewayRuntime.class);

    private final GatewayProperties props;
    private final IdentityStore identities;
    private final EnrolmentClient enrolment;
    private final ConfigService config;
    private final ConnectorRegistry connectors;
    private final EncryptedJournal journal;
    private volatile boolean ready;

    public GatewayRuntime(GatewayProperties props, IdentityStore identities, EnrolmentClient enrolment,
                          ConfigService config, ConnectorRegistry connectors, EncryptedJournal journal) {
        this.props = props;
        this.identities = identities;
        this.enrolment = enrolment;
        this.config = config;
        this.connectors = connectors;
        this.journal = journal;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureIdentity();
        if (props.releaseQuarantine() && journal.quarantined()) {
            journal.quarantine(false);
            log.warn("queue quarantine released by operator start-up flag");
        }
        config.loadPersisted();
        try {
            config.pull();
        } catch (IOException | RuntimeException e) {
            log.warn("configuration pull at start failed ({}); running on the stored / local configuration",
                    e.getClass().getSimpleName());
        }
        connectors.startAll();
        ready = true;
        log.info("gateway ready: role={} endpoint={} connectors={}", props.role(), identities.current().endpointId(),
                connectors.all().size());
    }

    void ensureIdentity() throws EnrolmentException, InterruptedException, IOException {
        EndpointIdentity id = identities.current();
        if (id != null) {
            return;
        }
        String token = props.enrolment().token();
        if ((token == null || token.isBlank()) && props.enrolment().tokenFile() != null
                && Files.exists(props.enrolment().tokenFile())) {
            token = Files.readString(props.enrolment().tokenFile(), StandardCharsets.UTF_8).trim();
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("No endpoint identity and no enrolment token: refusing to start. "
                    + "Set wz.gateway.enrolment.token (or token-file) to the one-time token from the device console.");
        }
        Backoff backoff = new Backoff(props.enrolment().initialBackoff(), props.enrolment().maxBackoff(),
                new java.security.SecureRandom());
        EndpointIdentity enrolled = enrolment.enrolWithRetry(token, backoff, props.enrolment().maxAttempts(),
                d -> Thread.sleep(d.toMillis()));
        identities.save(enrolled);
        if (props.enrolment().tokenFile() != null) {
            try {
                Files.deleteIfExists(props.enrolment().tokenFile()); // the token is single-use
            } catch (IOException e) {
                log.warn("enrolment token file could not be deleted; remove it manually");
            }
        }
    }

    public boolean ready() {
        return ready;
    }

    @Override
    public void destroy() {
        ready = false;
        connectors.stopAll();
    }
}
