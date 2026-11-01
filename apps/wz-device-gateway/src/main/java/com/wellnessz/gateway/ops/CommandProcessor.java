package com.wellnessz.gateway.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.forward.Forwarder;
import com.wellnessz.gateway.identity.EndpointIdentity;
import com.wellnessz.gateway.identity.IdentityStore;
import com.wellnessz.gateway.queue.EncryptedJournal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes gateway commands delivered in the heartbeat response (contract §7) and reports
 * {@code POST /device-ingest/commands/{commandId}/result}. A command id is executed at most once (the core may repeat a
 * command until it has seen the result).
 */
public class CommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(CommandProcessor.class);
    private static final int REMEMBERED = 256;

    /**
     * Result reported to the core.
     *
     * @param acks per-message acknowledgements of outbound messages ({@code controlId}, {@code code}, {@code text?})
     */
    public record Result(String status, String detail, List<Map<String, String>> acks) {
        public Result {
            acks = acks == null ? List.of() : List.copyOf(acks);
        }

        public Result(String status, String detail) {
            this(status, detail, List.of());
        }

        static Result done(String detail) {
            return new Result("DONE", detail);
        }

        static Result failed(String detail) {
            return new Result("FAILED", detail);
        }

        static Result done(String detail, List<Map<String, String>> acks) {
            return new Result("DONE", detail, acks);
        }

        static Result failed(String detail, List<Map<String, String>> acks) {
            return new Result("FAILED", detail, acks);
        }
    }

    private final CoreClient core;
    private final ConfigService config;
    private final IdentityStore identities;
    private final EncryptedJournal journal;
    private final Forwarder forwarder;
    private final WorklistService worklist;
    private final DeviceCommandService devices;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Result> executed = new LinkedHashMap<>(16, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > REMEMBERED;
        }
    };

    public CommandProcessor(CoreClient core, ConfigService config, IdentityStore identities, EncryptedJournal journal,
                            Forwarder forwarder, WorklistService worklist, Clock clock) {
        this(core, config, identities, journal, forwarder, worklist, null, clock);
    }

    public CommandProcessor(CoreClient core, ConfigService config, IdentityStore identities, EncryptedJournal journal,
                            Forwarder forwarder, WorklistService worklist, DeviceCommandService devices, Clock clock) {
        this.core = core;
        this.config = config;
        this.identities = identities;
        this.journal = journal;
        this.forwarder = forwarder;
        this.worklist = worklist;
        this.devices = devices;
        this.clock = clock;
    }

    /** Executes (once) and reports one command. */
    public synchronized Result handle(JsonNode command) {
        String id = command.path("commandId").asString("");
        String type = command.path("type").asString("");
        if (!id.matches("[A-Za-z0-9._:-]{1,64}")) {
            log.warn("command without a valid id ignored");
            return Result.failed("invalid_command_id");
        }
        Result result = executed.get(id);
        if (result == null) {
            result = execute(type, command);
            executed.put(id, result);
            log.info("command {} {} -> {}", id, type, result.status());
        }
        report(id, result);
        return result;
    }

    private Result execute(String type, JsonNode cmd) {
        try {
            return switch (type) {
                case "RELOAD_CONFIG" -> Result.done("configVersion " + config.pull());
                case "ROTATE_TOKEN" -> rotate(cmd.path("payload"));
                case "ISOLATE" -> {
                    journal.quarantine(true);
                    yield Result.done("queue quarantined; forwarding stopped");
                }
                case "PUSH_WORKLIST" -> {
                    String date = cmd.path("payload").path("date").asString(null);
                    LocalDate day = date == null ? LocalDate.now(clock.withZone(ZoneOffset.UTC)) : LocalDate.parse(date);
                    String instance = cmd.path("deviceInstanceId").asString(null);
                    if (devices == null) {
                        int n = worklist.push(instance, day);
                        yield Result.done(n + " worklist entries written");
                    }
                    yield devices.pushWorklist(cmd.path("commandId").asString(""), instance, day);
                }
                case "START_ACQUISITION" -> devices == null ? Result.failed("not_supported_by_connector")
                        : devices.startAcquisition(cmd.path("commandId").asString(""), cmd.path("deviceInstanceId").asString(null));
                case "RETRIEVE_HISTORY" -> devices == null ? Result.failed("not_supported_by_connector")
                        : devices.retrieveHistory(cmd.path("commandId").asString(""), cmd.path("deviceInstanceId").asString(null));
                case "RESEND_LAST" -> Result.failed("not_supported_by_connector");
                default -> Result.failed("unknown_command");
            };
        } catch (IOException | RuntimeException e) {
            return Result.failed(e.getClass().getSimpleName());
        }
    }

    private Result rotate(JsonNode payload) {
        String token = payload.path("endpointToken").asString(payload.path("token").asString(null));
        if (token == null || token.length() < 16) {
            return Result.failed("token_missing");
        }
        String expires = payload.path("tokenExpiresAt").asString(null);
        EndpointIdentity current = identities.current();
        identities.save(current.withToken(token, expires == null ? null : Instant.parse(expires)));
        forwarder.retryNow();
        return Result.done("token rotated");
    }

    private void report(String id, Result result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", result.status());
            if (result.detail() != null) {
                body.put("detail", result.detail());
            }
            if (!result.acks().isEmpty()) {
                body.put("acks", result.acks());
            }
            core.send("POST", "/device-ingest/commands/" + id + "/result", "application/json", Map.of(),
                    json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("command result for {} not delivered; it is re-sent when the core repeats the command", id);
        }
    }
}
