package io.intimed.engine.pipeline;

import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outcome per message: status (FILED, HELD_VERIFY, HELD_QUALITY, UNMATCHED, DUPLICATE, DLQ, REJECTED), ids, ack, timings. */
public final class PipelineResult {
    public String status;
    public String ackCode = "AA";
    public String error;
    public String configId;
    public final List<String> observationIds = new ArrayList<>();
    public final List<String> instanceIds = new ArrayList<>();
    public final List<ObjectNode> observations = new ArrayList<>();
    public final Map<String, Long> timingsMicros = new LinkedHashMap<>();
    public final List<String> routingErrors = new ArrayList<>();
    public String association;
    public double confidence;
    public String rawSha256;
    public String dlqId;
    public String unmatchedId;

    public static PipelineResult of(String status, String ack, String error) {
        PipelineResult r = new PipelineResult();
        r.status = status;
        r.ackCode = ack;
        r.error = error;
        return r;
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("ack", ackCode);
        if (error != null) m.put("error", error);
        m.put("configId", configId);
        m.put("observationIds", observationIds);
        m.put("instanceIds", instanceIds);
        m.put("association", association);
        m.put("confidence", confidence);
        m.put("timingsMicros", timingsMicros);
        if (!routingErrors.isEmpty()) m.put("routingErrors", routingErrors);
        if (dlqId != null) m.put("dlqId", dlqId);
        if (unmatchedId != null) m.put("unmatchedId", unmatchedId);
        m.put("rawSha256", rawSha256);
        return m;
    }
}
