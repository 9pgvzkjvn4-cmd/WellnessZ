package com.wellnessz.ehsintake.mailbox;

import java.util.List;

/** Mailbox access SPI: IMAP (any mail server) or Microsoft Graph (Exchange Online, modern auth). */
public interface MailboxReader {

    List<MailItem> fetchUnprocessed() throws Exception;

    void markProcessed(MailItem item) throws Exception;
}
