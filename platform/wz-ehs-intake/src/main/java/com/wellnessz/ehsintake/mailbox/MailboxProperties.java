package com.wellnessz.ehsintake.mailbox;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** EHS mailbox feed configuration. Secrets are supplied as {@code ENC(...)} or {@code file:} references. */
@ConfigurationProperties(prefix = "wellnessz.ehs.mailbox")
public class MailboxProperties {

    public enum Mode { NONE, IMAP, GRAPH }

    private Mode mode = Mode.NONE;
    /** Only mails from these addresses or domains (prefix with @) are processed – anti-spoofing control. */
    private List<String> allowedSenders = new ArrayList<>();
    private long maxAttachmentBytes = 10L * 1024 * 1024;
    private int maxMessagesPerPoll = 25;
    private final Imap imap = new Imap();
    private final Graph graph = new Graph();

    public static class Imap {
        private String host;
        private int port = 993;
        private boolean ssl = true;
        private String username;
        private String password;
        private String folder = "INBOX";
        private String processedFolder;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getFolder() {
            return folder;
        }

        public void setFolder(String folder) {
            this.folder = folder;
        }

        public String getProcessedFolder() {
            return processedFolder;
        }

        public void setProcessedFolder(String processedFolder) {
            this.processedFolder = processedFolder;
        }
    }

    public static class Graph {
        private String tenantId;
        private String clientId;
        private String clientSecret;
        private String mailbox;
        private String authority = "https://login.microsoftonline.com";
        private String endpoint = "https://graph.microsoft.com/v1.0";

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getMailbox() {
            return mailbox;
        }

        public void setMailbox(String mailbox) {
            this.mailbox = mailbox;
        }

        public String getAuthority() {
            return authority;
        }

        public void setAuthority(String authority) {
            this.authority = authority;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public List<String> getAllowedSenders() {
        return allowedSenders;
    }

    public void setAllowedSenders(List<String> allowedSenders) {
        this.allowedSenders = allowedSenders;
    }

    public long getMaxAttachmentBytes() {
        return maxAttachmentBytes;
    }

    public void setMaxAttachmentBytes(long maxAttachmentBytes) {
        this.maxAttachmentBytes = maxAttachmentBytes;
    }

    public int getMaxMessagesPerPoll() {
        return maxMessagesPerPoll;
    }

    public void setMaxMessagesPerPoll(int maxMessagesPerPoll) {
        this.maxMessagesPerPoll = maxMessagesPerPoll;
    }

    public Imap getImap() {
        return imap;
    }

    public Graph getGraph() {
        return graph;
    }
}
