package io.intimed.web;

import io.intimed.console.intelligence.ExplanationService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** LLM explanation layer behind the policy gateway (IM-E03-BL-006): policy status, tenant switch, ask with citations. */
@RestController
@RequestMapping("/api/intelligence/explain")
public class ExplanationController {
    private final ExplanationService explanations;

    public ExplanationController(ExplanationService explanations) { this.explanations = explanations; }

    private static void engineer() {
        Current.require(Role.INTEGRATION_DEV, Role.DEVICE_ADMIN, Role.TEMPLATE_OWNER, Role.PARTNER_DEV, Role.REGULATORY, Role.TENANT_ADMIN);
    }

    @GetMapping("/policy") public ObjectNode policy() { engineer(); return explanations.policy(Current.tenant()); }

    @PutMapping("/policy")
    public ObjectNode setPolicy(@RequestBody JsonNode b) {
        Current.require(Role.TENANT_ADMIN);
        explanations.setTenantEnabled(Current.tenant(), b.path("enabled").asBoolean(false), Current.actor());
        return explanations.policy(Current.tenant());
    }

    @PostMapping
    public ObjectNode explain(@RequestBody JsonNode b) {
        engineer();
        return explanations.explain(Current.tenant(), b.path("question").asString(null), Current.actor());
    }
}
