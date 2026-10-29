package com.wellnessz.ehsintake.mailbox;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;

/**
 * IMAP(S) reader. TLS with server-identity check is the default; plain IMAP is only allowed for the local
 * GreenMail test server. Messages are flagged SEEN (and optionally moved) after successful ingestion.
 */
public class ImapMailboxReader implements MailboxReader {

    private final MailboxProperties props;

    public ImapMailboxReader(MailboxProperties props) {
        this.props = props;
    }

    @Override
    public List<MailItem> fetchUnprocessed() throws MessagingException, IOException {
        MailboxProperties.Imap imap = props.getImap();
        try (Store store = connect(imap)) {
            Folder folder = store.getFolder(imap.getFolder());
            folder.open(Folder.READ_ONLY);
            try {
                Message[] unseen = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                List<MailItem> items = new ArrayList<>();
                for (int i = 0; i < unseen.length && items.size() < props.getMaxMessagesPerPoll(); i++) {
                    items.add(toItem(unseen[i]));
                }
                return items;
            } finally {
                folder.close(false);
            }
        }
    }

    @Override
    public void markProcessed(MailItem item) throws MessagingException {
        MailboxProperties.Imap imap = props.getImap();
        try (Store store = connect(imap)) {
            Folder folder = store.getFolder(imap.getFolder());
            folder.open(Folder.READ_WRITE);
            try {
                for (Message m : folder.getMessages()) {
                    String[] ids = m.getHeader("Message-ID");
                    if (ids != null && ids.length > 0 && ids[0].equals(item.id())) {
                        m.setFlag(Flags.Flag.SEEN, true);
                        if (imap.getProcessedFolder() != null) {
                            Folder target = store.getFolder(imap.getProcessedFolder());
                            if (!target.exists()) {
                                target.create(Folder.HOLDS_MESSAGES);
                            }
                            folder.copyMessages(new Message[]{m}, target);
                            m.setFlag(Flags.Flag.DELETED, true);
                        }
                    }
                }
            } finally {
                folder.close(true);
            }
        }
    }

    private Store connect(MailboxProperties.Imap imap) throws MessagingException {
        Properties p = new Properties();
        String protocol = imap.isSsl() ? "imaps" : "imap";
        p.put("mail.store.protocol", protocol);
        p.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        p.put("mail." + protocol + ".connectiontimeout", "15000");
        p.put("mail." + protocol + ".timeout", "30000");
        Session session = Session.getInstance(p);
        Store store = session.getStore(protocol);
        store.connect(imap.getHost(), imap.getPort(), imap.getUsername(), imap.getPassword());
        return store;
    }

    private MailItem toItem(Message m) throws MessagingException, IOException {
        String[] ids = m.getHeader("Message-ID");
        Address[] from = m.getFrom();
        String sender = from != null && from.length > 0 && from[0] instanceof InternetAddress ia ? ia.getAddress() : null;
        List<MailItem.Attachment> attachments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        collect(m, text, attachments);
        return new MailItem(ids != null && ids.length > 0 ? ids[0] : null, sender, m.getSubject(), text.toString(), attachments);
    }

    private void collect(Part part, StringBuilder text, List<MailItem.Attachment> attachments) throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                collect(bp, text, attachments);
            }
        } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
            if (part.getSize() > props.getMaxAttachmentBytes()) {
                return;
            }
            try (InputStream in = part.getInputStream()) {
                byte[] bytes = in.readNBytes((int) props.getMaxAttachmentBytes() + 1);
                if (bytes.length <= props.getMaxAttachmentBytes()) {
                    attachments.add(new MailItem.Attachment(part.getFileName(), part.getContentType(), bytes));
                }
            }
        } else if (part.isMimeType("text/plain")) {
            text.append(part.getContent());
        }
    }
}
