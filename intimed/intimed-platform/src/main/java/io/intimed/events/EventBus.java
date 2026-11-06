package io.intimed.events;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Event bus with a transactional-outbox style store (IM-E07-F03, EP-4): every event is persisted (replayable) and then
 * dispatched to in-process listeners (webhook dispatcher, metering). Event bodies carry ids and statuses only – never
 * PHI; consumers read the data through the FHIR API with their own scopes. Kafka/Event Hubs/Pub/Sub replace the
 * in-process dispatch in DEV/UAT/PROD behind the same publish contract.
 */
@Component
public class EventBus {
    public static final List<String> TYPES = List.of("observation.posted", "observation.held", "observation.unmatched", "device.alert",
            "device.certified", "device.published", "run.completed", "identity.revoked", "identity.isolated", "config.changed",
            "regression.failed");
    private final DocumentStore store;
    private final List<BiConsumer<String, ObjectNode>> listeners = new CopyOnWriteArrayList<>();

    public EventBus(DocumentStore store) { this.store = store; }

    public void subscribe(BiConsumer<String, ObjectNode> l) { listeners.add(l); }

    public ObjectNode publish(String tenant, String type, JsonNode data) {
        String id = Hashing.id("evt");
        ObjectNode e = Json.obj().put("id", id).put("type", type).put("tenant", tenant).put("time", Instant.now().toString());
        e.set("data", data);
        store.put(tenant, "event", id, e, type, null);
        for (BiConsumer<String, ObjectNode> l : listeners) {
            try { l.accept(tenant, e); } catch (RuntimeException ignored) { /* a failing consumer never blocks the pipeline */ }
        }
        return e;
    }

    public List<ObjectNode> recent(String tenant, int n) {
        List<ObjectNode> all = store.list(tenant, "event");
        return all.subList(Math.max(0, all.size() - n), all.size()).reversed();
    }
}
