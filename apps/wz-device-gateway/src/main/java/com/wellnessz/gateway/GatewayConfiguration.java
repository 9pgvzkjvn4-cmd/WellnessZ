package com.wellnessz.gateway;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.ConnectorRegistry;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.core.Backoff;
import com.wellnessz.gateway.core.ClockSkew;
import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.core.SecretClient;
import com.wellnessz.gateway.crypto.GatewayKeys;
import com.wellnessz.gateway.forward.Forwarder;
import com.wellnessz.gateway.identity.ClientCertificate;
import com.wellnessz.gateway.identity.EndpointIdentity;
import com.wellnessz.gateway.identity.EnrolmentClient;
import com.wellnessz.gateway.identity.IdentityStore;
import com.wellnessz.gateway.ops.CommandProcessor;
import com.wellnessz.gateway.ops.ConfigService;
import com.wellnessz.gateway.ops.DeviceCommandService;
import com.wellnessz.gateway.ops.HardeningCheck;
import com.wellnessz.gateway.ops.HeartbeatService;
import com.wellnessz.gateway.ops.RuntimeSettings;
import com.wellnessz.gateway.ops.WorklistService;
import com.wellnessz.gateway.queue.EncryptedJournal;

import io.micrometer.core.instrument.MeterRegistry;

/** Wiring of the edge runtime (plain objects, constructed once). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
@EnableScheduling
public class GatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    GatewayKeys gatewayKeys(GatewayProperties p, Environment env) {
        return GatewayKeys.load(env::getProperty, p.dataDir());
    }

    @Bean
    IdentityStore identityStore(GatewayProperties p, GatewayKeys keys) {
        return new IdentityStore(p.dataDir(), keys.cipher(GatewayKeys.IDENTITY_PURPOSE));
    }

    @Bean
    ClientCertificate clientCertificate(GatewayProperties p) {
        return ClientCertificate.load(p.tls());
    }

    @Bean
    ClockSkew clockSkew() {
        return new ClockSkew();
    }

    @Bean
    CoreClient coreClient(GatewayProperties p, ClientCertificate cert, IdentityStore ids, Clock clock) {
        return new CoreClient(p, cert, ids::current, clock);
    }

    @Bean(destroyMethod = "close")
    EncryptedJournal encryptedJournal(GatewayProperties p, GatewayKeys keys, IdentityStore ids, Clock clock) {
        return new EncryptedJournal(p.dataDir().resolve("queue"), keys.cipher(GatewayKeys.JOURNAL_PURPOSE), () -> {
            EndpointIdentity id = ids.current();
            if (id == null) {
                throw new IllegalStateException("gateway is not enrolled");
            }
            return id.endpointId();
        }, p.queue().maxBytes(), p.queue().segmentBytes(), clock);
    }

    @Bean
    Forwarder forwarder(GatewayProperties p, EncryptedJournal journal, CoreClient core, Clock clock,
                        MeterRegistry metrics) {
        return new Forwarder(journal, core, p.forwarder(), clock,
                new Backoff(p.forwarder().initialBackoff(), p.forwarder().maxBackoff(), new SecureRandom()), metrics);
    }

    @Bean
    IngestSink ingestSink(EncryptedJournal journal, Forwarder forwarder, MeterRegistry metrics) {
        return new IngestSink(journal, forwarder, metrics);
    }

    @Bean
    SecretClient secretClient(CoreClient core) {
        return new SecretClient(core);
    }

    @Bean
    ConnectorRegistry connectorRegistry(GatewayProperties p, IngestSink sink, Clock clock, SecretClient secrets,
                                        GatewayKeys keys, Environment env) {
        String salt = HexFormat.of().formatHex(keys.derive("wz-gateway/ble-address-salt/v1"));
        // contract §16.1: "--simulate-serial <file>" (or "-" for stdin) replays a captured serial stream for tests / demos
        String sim = env.getProperty("simulate-serial");
        GatewayProperties.Serial simulated = null;
        if (sim != null && !sim.isBlank()) {
            boolean stdin = "-".equals(sim.trim());
            simulated = new GatewayProperties.Serial("serial-sim", env.getProperty("simulate-serial-device"),
                    stdin ? GatewayProperties.SerialMode.STDIN : GatewayProperties.SerialMode.FILE, null, 0,
                    stdin ? null : java.nio.file.Path.of(sim.trim()), "SIM", GatewayProperties.Framing.LINE, 3,
                    "TEXT", 65536, java.time.Duration.ofSeconds(5));
        }
        return new ConnectorRegistry(p, sink, clock, secrets, salt, simulated);
    }

    @Bean
    RuntimeSettings runtimeSettings(GatewayProperties p) {
        return new RuntimeSettings(p);
    }

    @Bean
    ConfigService configService(CoreClient core, GatewayKeys keys, GatewayProperties p, RuntimeSettings settings,
                                EncryptedJournal journal, ConnectorRegistry connectors) {
        ConfigService cs = new ConfigService(core, keys.cipher(GatewayKeys.STATE_PURPOSE), p.dataDir(), settings,
                journal, LoggingSystem.get(GatewayConfiguration.class.getClassLoader()));
        connectors.ble().ifPresent(ble -> cs.onBleAllowList(ble::allowList));
        return cs;
    }

    @Bean
    WorklistService worklistService(CoreClient core, GatewayProperties p) {
        return new WorklistService(core, p.worklist());
    }

    @Bean
    DeviceCommandService deviceCommandService(CoreClient core, WorklistService worklist, GatewayProperties p,
                                              ConnectorRegistry connectors) {
        return new DeviceCommandService(core, worklist, p.mllpOutbound(), p.allowPlaintextListeners(), connectors::all);
    }

    @Bean
    CommandProcessor commandProcessor(CoreClient core, ConfigService config, IdentityStore ids,
                                      EncryptedJournal journal, Forwarder forwarder, WorklistService worklist,
                                      DeviceCommandService devices, Clock clock) {
        return new CommandProcessor(core, config, ids, journal, forwarder, worklist, devices, clock);
    }

    @Bean
    HardeningCheck hardeningCheck(GatewayProperties p, ClientCertificate cert, ClockSkew skew, Clock clock,
                                  Environment env, ConnectorRegistry connectors) {
        return new HardeningCheck(p, cert, skew, clock, env.getProperty("server.address", "0.0.0.0"),
                connectors::plaintextListeners);
    }

    @Bean
    HeartbeatService heartbeatService(CoreClient core, EncryptedJournal journal, ConnectorRegistry connectors,
                                      HardeningCheck hardening, ConfigService config, CommandProcessor commands,
                                      ClockSkew skew, RuntimeSettings settings, Clock clock, GatewayProperties p) {
        return new HeartbeatService(core, journal, connectors::all, hardening, config, commands, skew, settings, clock,
                GatewayApplication.version(), p.role().name());
    }

    @Bean
    EnrolmentClient enrolmentClient(CoreClient core, ClientCertificate cert, GatewayProperties p,
                                    HardeningCheck hardening, Clock clock) {
        return new EnrolmentClient(core, cert, p, () -> {
            var h = hardening.run();
            return java.util.Map.of("baseline", h.get("baseline"), "status", h.get("status"));
        }, clock);
    }

    @Bean
    GatewayRuntime gatewayRuntime(GatewayProperties p, IdentityStore ids, EnrolmentClient enrolment,
                                  ConfigService config, ConnectorRegistry connectors, EncryptedJournal journal) {
        return new GatewayRuntime(p, ids, enrolment, config, connectors, journal);
    }
}
