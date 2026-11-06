package io.intimed.web;

import io.intimed.catalogue.CatalogueService;
import io.intimed.catalogue.RegisterFeedService;
import io.intimed.catalogue.UdiFeedService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/** UDI register connectors (IM-E02-BL-003): AccessGUDID lookup, EUDAMED search, connector status, approved UDI records. */
@RestController
@RequestMapping("/api/catalogue/feeds")
public class RegisterFeedController {
    private final UdiFeedService udi;
    private final RegisterFeedService openFda;
    private final CatalogueService catalogue;

    public RegisterFeedController(UdiFeedService udi, RegisterFeedService openFda, CatalogueService catalogue) {
        this.udi = udi;
        this.openFda = openFda;
        this.catalogue = catalogue;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(udi.status());
        m.put("openfda", Map.of("enabled", openFda.enabled(), "host", RegisterFeedService.HOST));
        return m;
    }

    @PostMapping("/gudid/lookup")
    public ObjectNode gudid(@RequestBody JsonNode b) {
        Current.require(Role.DEVICE_ADMIN, Role.REGULATORY);
        return udi.gudidLookup(Current.tenant(), b.path("di").asString(null), b.path("modelId").asString(null), Current.actor());
    }

    @PostMapping("/eudamed/search")
    public ObjectNode eudamed(@RequestBody JsonNode b) {
        Current.require(Role.DEVICE_ADMIN, Role.REGULATORY);
        return udi.eudamedSearch(Current.tenant(), b.path("di").asString(null), b.path("q").asString(null), b.path("modelId").asString(null), Current.actor());
    }

    @GetMapping("/udi-records") public List<ObjectNode> records() { return catalogue.udiRecords(Current.tenant()); }
}
