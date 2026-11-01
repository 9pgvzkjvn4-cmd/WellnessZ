package io.intimed.web;

import io.intimed.catalogue.CatalogueService;
import io.intimed.events.EventBus;
import io.intimed.harness.HarnessService;
import io.intimed.security.auth.Current;
import io.intimed.store.DocumentStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/** Machine API v1 (bearer tokens): harness for driver CI / partner tests (IM-E04-BL-008), catalogue, events. */
@RestController
@RequestMapping("/api/v1")
public class MachineApiController {
    private final HarnessService harness;
    private final CatalogueService catalogue;
    private final EventBus events;
    private final DocumentStore store;

    public MachineApiController(HarnessService harness, CatalogueService catalogue, EventBus events, DocumentStore store) {
        this.harness = harness;
        this.catalogue = catalogue;
        this.events = events;
        this.store = store;
    }

    @PostMapping("/harness/runs")
    public ObjectNode run(@RequestBody JsonNode b) {
        Current.requireScope("harness.run");
        return harness.run(Current.tenant(), b.path("configId").asString(), Current.actor());
    }

    @GetMapping("/harness/runs/{id}")
    public ObjectNode get(@PathVariable String id) { Current.requireScope("harness.run"); return store.require(Current.tenant(), "run", id); }

    @GetMapping("/catalogue/models")
    public List<ObjectNode> models(@RequestParam(required = false) String q) { Current.requireScope("catalogue.read"); return catalogue.search(Current.tenant(), q, null, null, null, null, 100); }

    @GetMapping("/events")
    public List<ObjectNode> events() { Current.requireScope("events.read"); return events.recent(Current.tenant(), 100); }
}
