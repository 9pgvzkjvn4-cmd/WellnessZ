package com.wellnessz.ehsintake.mailbox;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Microsoft Graph reader for Exchange Online (basic-auth IMAP is retired in M365). Uses the OAuth2 client-credentials
 * flow with the {@code Mail.ReadWrite} application permission, which <b>must</b> be scoped to the single EHS feed
 * mailbox using Exchange "RBAC for Applications" / an application access policy.
 */
public class GraphMailboxReader implements MailboxReader {

    private final MailboxProperties.Graph cfg;
    private final int maxMessages;
    private final long maxAttachmentBytes;
    private final RestClient http;
    private final JsonMapper json;
    private final Clock clock;
    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    public GraphMailboxReader(MailboxProperties props, RestClient.Builder builder, JsonMapper json, Clock clock) {
        this.cfg = props.getGraph();
        this.maxMessages = props.getMaxMessagesPerPoll();
        this.maxAttachmentBytes = props.getMaxAttachmentBytes();
        this.http = builder.build();
        this.json = json;
        this.clock = clock;
    }

    @Override
    public List<MailItem> fetchUnprocessed() {
        String url = cfg.getEndpoint() + "/users/" + enc(cfg.getMailbox())
                + "/mailFolders/inbox/messages?$filter=isRead%20eq%20false&$top=" + maxMessages
                + "&$select=id,subject,from,body,hasAttachments";
        JsonNode page = get(url);
        List<MailItem> items = new ArrayList<>();
        for (JsonNode m : page.path("value")) {
            String id = m.path("id").asString();
            List<MailItem.Attachment> attachments = new ArrayList<>();
            if (m.path("hasAttachments").asBoolean(false)) {
                JsonNode atts = get(cfg.getEndpoint() + "/users/" + enc(cfg.getMailbox()) + "/messages/" + enc(id) + "/attachments");
                for (JsonNode a : atts.path("value")) {
                    if ("#microsoft.graph.fileAttachment".equals(a.path("@odata.type").asString())
                            && a.path("size").asLong(Long.MAX_VALUE) <= maxAttachmentBytes) {
                        attachments.add(new MailItem.Attachment(a.path("name").asString(), a.path("contentType").asString(),
                                Base64.getDecoder().decode(a.path("contentBytes").asString())));
                    }
                }
            }
            String body = m.path("body").path("content").asString("");
            if ("html".equalsIgnoreCase(m.path("body").path("contentType").asString())) {
                body = body.replaceAll("(?i)<br\\s*/?>|</p>|</div>", "\n").replaceAll("<[^>]+>", "");
            }
            items.add(new MailItem(id, m.path("from").path("emailAddress").path("address").asString(null),
                    m.path("subject").asString(""), body, attachments));
        }
        return items;
    }

    @Override
    public void markProcessed(MailItem item) {
        http.patch().uri(cfg.getEndpoint() + "/users/" + enc(cfg.getMailbox()) + "/messages/" + enc(item.id()))
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isRead\":true}")
                .retrieve().toBodilessEntity();
    }

    private JsonNode get(String url) {
        String body = http.get().uri(java.net.URI.create(url)).header("Authorization", "Bearer " + token())
                .retrieve().body(String.class);
        return json.readTree(body == null ? "{}" : body);
    }

    private synchronized String token() {
        if (token != null && clock.instant().isBefore(tokenExpiry.minusSeconds(60))) {
            return token;
        }
        String form = "grant_type=client_credentials&scope=" + enc("https://graph.microsoft.com/.default")
                + "&client_id=" + enc(cfg.getClientId()) + "&client_secret=" + enc(cfg.getClientSecret());
        String response = http.post().uri(cfg.getAuthority() + "/" + enc(cfg.getTenantId()) + "/oauth2/v2.0/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
        JsonNode node = json.readTree(response);
        token = node.path("access_token").asString();
        tokenExpiry = clock.instant().plusSeconds(node.path("expires_in").asLong(300));
        return token;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
