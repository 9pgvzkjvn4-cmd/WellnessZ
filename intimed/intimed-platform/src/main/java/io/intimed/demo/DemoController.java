package io.intimed.demo;

import io.intimed.common.ApiException;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/** Demo control (demo profile only): scenario status, run all / one, live streaming, anomaly drill trigger. */
@RestController
@RequestMapping("/api/demo")
public class DemoController {
    private final DemoService demo;
    private final io.intimed.intelligence.DriftMonitor drift;

    public DemoController(DemoService demo, io.intimed.intelligence.DriftMonitor drift) { this.demo = demo; this.drift = drift; }

    private void guard() {
        if (!demo.enabled()) throw ApiException.notFound("resource");
        if (!DemoService.TENANT.equals(Current.tenant())) throw ApiException.forbidden("demo-tenant", "demo scenarios run in the acme-health tenant");
    }

    @GetMapping("/status") public Map<String, Object> status() { guard(); return demo.status(); }
    @GetMapping("/personas") public JsonNode personas() { guard(); return demo.personas(); }

    @PostMapping("/run-all")
    public Map<String, Object> runAll(@RequestParam(defaultValue = "true") boolean includeGuided) {
        guard();
        Current.require(Role.DEVICE_ADMIN, Role.TENANT_ADMIN, Role.PLATFORM_SRE);
        Thread.ofVirtual().start(() -> demo.runAll(includeGuided));
        return Map.of("started", true);
    }

    @PostMapping("/scenarios/{tpl}") public ObjectNode one(@PathVariable String tpl) { guard(); Current.require(Role.DEVICE_ADMIN, Role.TENANT_ADMIN); return demo.runScenario(tpl); }

    @PostMapping("/streaming") public Map<String, Object> streaming(@RequestParam boolean on) { guard(); demo.setStreaming(on); return Map.of("streaming", on); }

    @PostMapping("/drift-drill")
    public ObjectNode driftDrill() { guard(); Current.require(Role.DEVICE_ADMIN, Role.TENANT_ADMIN); return demo.driftDrill(drift); }

    @PostMapping("/anomaly-drill/{configId}")
    public ObjectNode drill(@PathVariable String configId) { guard(); Current.require(Role.SECURITY_ANALYST); return demo.anomalyDrill(configId); }
}
