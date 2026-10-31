package com.wellnessz.gateway.connector.dicom;

import com.wellnessz.gateway.support.PathNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.config.GatewayProperties.PostAction;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.connector.file.PolledFolderConnector;
import com.wellnessz.gateway.queue.ItemKind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * P7 DICOM drop folder (contract §16.1): the modality or PACS export places Part 10 instances ({@code *.dcm}) in a
 * folder; the gateway queues instance + PS3.18 JSON metadata (from a {@code <name>.json} sidecar when the modality
 * writes one, otherwise from {@link DicomMetadata}) and the forwarder uploads the instance through
 * {@code /device-ingest/attachments} before posting the metadata with its {@code archiveId} to
 * {@code /device-ingest/dicomweb}. Instances are moved to the archive (the drop folder belongs to the gateway).
 */
public class DicomDropConnector extends PolledFolderConnector {

    private final JsonMapper json = JsonMapper.builder().build();

    public DicomDropConnector(GatewayProperties.DicomDrop cfg, IngestSink sink, Clock clock, Path dataDir) {
        super(cfg.id(), cfg.deviceInstanceId(), sink, clock, cfg.path(), "*.dcm", cfg.archive(), cfg.quarantine(),
                PostAction.MOVE, cfg.stabilitySeconds(), cfg.maxFileBytes(), cfg.pollInterval(), dataDir);
    }

    @Override
    public String type() {
        return "DICOM";
    }

    @Override
    protected Decision process(Path file, byte[] content, String sha256) {
        if (!DicomMetadata.isPart10(content)) {
            return Decision.quarantine("dicom_invalid");
        }
        JsonNode metadata = sidecar(file);
        if (metadata == null) {
            metadata = DicomMetadata.extract(content, json);
        }
        if (!metadata.has("00080018")) {
            return Decision.quarantine("dicom_sop_instance_missing");
        }
        ArrayNode datasets = metadata.isArray() ? (ArrayNode) metadata : json.createArrayNode().add(metadata);
        ObjectNode envelope = json.createObjectNode();
        envelope.set("metadata", datasets);
        envelope.put("sha256", sha256);
        envelope.put("instance", Base64.getEncoder().encodeToString(content));
        sink.capture(type(), ItemKind.DICOM, deviceInstanceId, "application/dicom+json",
                Map.of("X-File-Name", headerSafe(PathNames.name(file))),
                json.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8), sha256);
        return Decision.queued();
    }

    private JsonNode sidecar(Path file) {
        String name = PathNames.name(file);
        Path side = file.resolveSibling(name.substring(0, name.length() - 4) + ".json");
        if (!Files.isRegularFile(side, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            JsonNode n = json.readTree(Files.readString(side, StandardCharsets.UTF_8));
            if (n.isArray() && n.size() == 1) {
                n = n.get(0);
            }
            return n.isObject() ? n : null;
        } catch (IOException | tools.jackson.core.JacksonException e) {
            return null;
        }
    }
}
