package com.wellnessz.gateway.queue;

/** Message kinds held in the local queue, with the ingest path they are forwarded to (contract §7). */
public enum ItemKind {
    HL7V2("/device-ingest/hl7v2", true),
    FHIR("/device-ingest/fhir", true),
    POCT1A("/device-ingest/poct1a", true),
    FILE("/device-ingest/files", true),
    BLE("/device-ingest/ble", true),
    SERIAL("/device-ingest/serial", true),
    FALLBACK("/device-ingest/fallback", true),
    /** Instance bytes + PS3.18 JSON metadata: two single calls (/attachments then /dicomweb), never batched. */
    DICOM("/device-ingest/dicomweb", false);

    private final String path;
    private final boolean batchable;

    ItemKind(String path, boolean batchable) {
        this.path = path;
        this.batchable = batchable;
    }

    public String path() {
        return path;
    }

    /** {@code true} when the batch API accepts this kind ({@code items[].kind}). */
    public boolean batchable() {
        return batchable;
    }
}
