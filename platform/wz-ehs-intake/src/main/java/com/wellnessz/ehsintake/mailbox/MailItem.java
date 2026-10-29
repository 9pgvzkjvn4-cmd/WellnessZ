package com.wellnessz.ehsintake.mailbox;

import java.util.List;

/** Provider-neutral representation of an EHS feed e-mail. */
public record MailItem(String id, String from, String subject, String textBody, List<Attachment> attachments) {

    public record Attachment(String name, String contentType, byte[] content) {
    }
}
