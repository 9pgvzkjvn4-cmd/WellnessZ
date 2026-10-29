package com.wellnessz.ehsintake.mailbox;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.wellnessz.ehsintake.IncidentIntakeService;
import com.wellnessz.ehsintake.model.IncidentRecord;
import com.wellnessz.ehsintake.model.IntakeChannel;
import com.wellnessz.security.audit.AuditService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Polls the EHS feed mailbox. Each message is processed independently; it is marked processed only after its
 * incidents are committed, so a crash results in re-processing, which is safe because ingestion de-duplicates
 * on (source system, incident id).
 */
@Component
@EnableConfigurationProperties(MailboxProperties.class)
public class MailboxIngestionJob {

    private static final Logger log = LoggerFactory.getLogger(MailboxIngestionJob.class);

    private final MailboxProperties props;
    private final IncidentIntakeService intake;
    private final AuditService audit;
    private final MailboxReader reader;
    private final String sourceSystem;
    private final ZoneId zone;

    public MailboxIngestionJob(MailboxProperties props, IncidentIntakeService intake, AuditService audit,
                               RestClient.Builder restClientBuilder, JsonMapper json, Clock clock,
                               @Value("${wellnessz.ehs.source-system:EHS}") String sourceSystem,
                               @Value("${wellnessz.ehs.timezone:UTC}") String zone) {
        this.props = props;
        this.intake = intake;
        this.audit = audit;
        this.sourceSystem = sourceSystem;
        this.zone = ZoneId.of(zone);
        this.reader = switch (props.getMode()) {
            case IMAP -> new ImapMailboxReader(props);
            case GRAPH -> new GraphMailboxReader(props, restClientBuilder, json, clock);
            case NONE -> null;
        };
    }

    public record PollResult(int messages, int processed, int rejected) {
    }

    @Scheduled(fixedDelayString = "${wellnessz.ehs.mailbox.poll-interval:PT5M}", initialDelayString = "PT30S")
    public void scheduledPoll() {
        if (reader != null) {
            poll();
        }
    }

    public synchronized PollResult poll() {
        if (reader == null) {
            return new PollResult(0, 0, 0);
        }
        IntakeChannel channel = props.getMode() == MailboxProperties.Mode.GRAPH ? IntakeChannel.MAILBOX_GRAPH : IntakeChannel.MAILBOX_IMAP;
        List<MailItem> items;
        try {
            items = reader.fetchUnprocessed();
        } catch (Exception e) {
            log.error("EHS mailbox poll failed: {}", e.getClass().getSimpleName());
            audit.recordAs("system", "", "EHS_MAILBOX_POLL", "MAILBOX", null, AuditService.Outcome.FAILURE, e.getClass().getSimpleName());
            return new PollResult(0, 0, 0);
        }
        int processed = 0;
        int rejected = 0;
        for (MailItem item : items) {
            try {
                if (!senderAllowed(item.from())) {
                    rejected++;
                    audit.recordAs("system", "", "EHS_MAIL_REJECTED", "MAILBOX", item.id(), AuditService.Outcome.DENIED, "Sender not allow-listed");
                } else {
                    ingest(item, channel);
                    processed++;
                }
                reader.markProcessed(item);
            } catch (Exception e) {
                log.warn("Failed to ingest EHS mail: {}", e.getClass().getSimpleName());
            }
        }
        return new PollResult(items.size(), processed, rejected);
    }

    private void ingest(MailItem item, IntakeChannel channel) {
        boolean hadWorkbook = false;
        for (MailItem.Attachment a : item.attachments()) {
            if (a.name() != null && a.name().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                intake.importExcel(a.content(), "mail:" + a.name(), channel, "ehs-mailbox");
                hadWorkbook = true;
            }
        }
        if (!hadWorkbook) {
            Optional<IncidentRecord> record = MailBodyParser.parse(item.textBody() == null ? "" : item.textBody(), sourceSystem, zone);
            record.ifPresent(r -> intake.importRecords(List.of(r), "mail:" + item.subject(), channel, "ehs-mailbox"));
        }
    }

    boolean senderAllowed(String from) {
        if (from == null) {
            return false;
        }
        String f = from.toLowerCase(Locale.ROOT).trim();
        return props.getAllowedSenders().stream().map(s -> s.toLowerCase(Locale.ROOT).trim())
                .anyMatch(s -> s.startsWith("@") ? f.endsWith(s) : f.equals(s));
    }
}
