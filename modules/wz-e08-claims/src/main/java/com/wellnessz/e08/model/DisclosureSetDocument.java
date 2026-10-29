package com.wellnessz.e08.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One document of a disclosure set (a redacted copy replaces its original). */
@Entity(name = "E08DisclosureSetDocument")
@Table(name = "disclosure_set_document", schema = "claims")
public class DisclosureSetDocument {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "set_id")
    private UUID setId;
    @Column(name = "position")
    private int position;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "title")
    private String title;
    @Column(name = "doc_type")
    private String docType;
    @Column(name = "redacted_from_id")
    private UUID redactedFromId;
    @Column(name = "added_by")
    private String addedBy;
    @Column(name = "added_by_oh")
    private boolean addedByOh;
    @Column(name = "share_id")
    private UUID shareId;

    public DisclosureSetDocument() {
        // JPA; populated through setters by the owning service
    }

    public static DisclosureSetDocument create() {
        DisclosureSetDocument x = new DisclosureSetDocument();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSetId() { return setId; }
    public void setSetId(UUID v) { this.setId = v; }
    public int getPosition() { return position; }
    public void setPosition(int v) { this.position = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getDocType() { return docType; }
    public void setDocType(String v) { this.docType = v; }
    public UUID getRedactedFromId() { return redactedFromId; }
    public void setRedactedFromId(UUID v) { this.redactedFromId = v; }
    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String v) { this.addedBy = v; }
    public boolean isAddedByOh() { return addedByOh; }
    public void setAddedByOh(boolean v) { this.addedByOh = v; }
    public UUID getShareId() { return shareId; }
    public void setShareId(UUID v) { this.shareId = v; }
}
