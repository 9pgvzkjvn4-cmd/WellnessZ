package com.wellnessz.e32.web;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e32.catalogue.CatalogueService;
import com.wellnessz.e32.config.StationStore;
import com.wellnessz.e32.driver.DriverPackageRules;
import com.wellnessz.e32.driver.DriverRegistryService;
import com.wellnessz.e32.option.OptionPacks;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E32-F01 / F03 / F07: catalogue, regulatory register, option packs, driver registry and vendor posture (contract §13.1, §13.8). */
@RestController("e32CatalogueController")
@RequestMapping("/api/v1/device-catalogue")
public class CatalogueController {

    private final CatalogueService catalogue;
    private final DriverRegistryService registry;
    private final OptionPacks packs;
    private final StationStore stations;
    private final CurrentUserProvider users;

    public CatalogueController(CatalogueService catalogue, DriverRegistryService registry, OptionPacks packs, StationStore stations,
                               CurrentUserProvider users) {
        this.catalogue = catalogue;
        this.registry = registry;
        this.packs = packs;
        this.stations = stations;
        this.users = users;
    }

    @GetMapping("/families")
    @PreAuthorize(Spel.CATALOGUE_READ)
    public List<Map<String, Object>> families() {
        return catalogue.families();
    }

    @GetMapping("/models")
    @PreAuthorize(Spel.CATALOGUE_READ)
    public List<Map<String, Object>> models(@RequestParam(required = false) @Pattern(regexp = Spel.CODE) String family,
                                            @RequestParam(required = false) @Pattern(regexp = "[\\p{L}\\p{N} .&/+\\-]{1,80}") String q,
                                            @RequestParam(required = false) @Pattern(regexp = "P([1-9]|10)") String option,
                                            @RequestParam(required = false) @Pattern(regexp = Spel.CODE) String lifecycle,
                                            @RequestParam(required = false) @Pattern(regexp = Spel.CODE) String posture,
                                            @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{2,30}") String tenantCode) {
        return catalogue.search(family, q, option, lifecycle, posture, tenantCode);
    }

    @GetMapping("/models/{modelCode}")
    @PreAuthorize(Spel.CATALOGUE_READ)
    public Map<String, Object> model(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode) {
        return catalogue.get(modelCode);
    }

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> create(@RequestBody CatalogueService.ModelCommand cmd) {
        return catalogue.create(users.require(), cmd);
    }

    public record TenantRequest(@Pattern(regexp = "[A-Z0-9_]{2,30}") String tenantCode) {
    }

    /** Scopes a model to one tenant's catalogue ({@code tenantCode} null / GROUP = shared group catalogue; E31-HLR-12). */
    @PutMapping("/models/{modelCode}/tenant")
    @PreAuthorize("hasAnyRole('DEVICE_ADMIN','PLATFORM_ADMIN')")
    public Map<String, Object> modelTenant(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode,
                                           @RequestBody @jakarta.validation.Valid TenantRequest r) {
        return catalogue.setTenant(users.require(), modelCode, r.tenantCode());
    }

    @PutMapping("/models/{modelCode}")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> update(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode, @RequestBody Map<String, Object> changes) {
        return catalogue.update(users.require(), modelCode, changes);
    }

    public record LifecycleCommand(String to, String reason) {
    }

    @PostMapping("/models/{modelCode}/lifecycle")
    @PreAuthorize(Spel.LIFECYCLE)
    public Map<String, Object> lifecycle(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode, @RequestBody LifecycleCommand cmd) {
        return catalogue.lifecycle(users.require(), modelCode, cmd.to(), cmd.reason());
    }

    @PostMapping("/models/{modelCode}/udi")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> udi(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode, @RequestBody CatalogueService.UdiCommand cmd) {
        return catalogue.importUdi(users.require(), modelCode, cmd);
    }

    @PutMapping("/models/{modelCode}/posture")
    @PreAuthorize(Spel.POSTURE)
    public Map<String, Object> posture(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode, @RequestBody Map<String, Object> intake) {
        return catalogue.putPosture(users.require(), modelCode, intake);
    }

    @GetMapping("/models/{modelCode}/posture/exceptions")
    @PreAuthorize(Spel.DEVICE_ROLES)
    public List<Map<String, Object>> exceptions(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode) {
        return catalogue.exceptions(modelCode);
    }

    @PostMapping("/models/{modelCode}/posture/exceptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> requestException(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode,
                                                @RequestBody CatalogueService.ExceptionCommand cmd) {
        return catalogue.requestException(users.require(), modelCode, cmd);
    }

    public record Decision(String note) {
    }

    @PostMapping("/models/{modelCode}/posture/exceptions/{id}/approve")
    @PreAuthorize(Spel.SECOPS)
    public Map<String, Object> approveException(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode, @PathVariable UUID id,
                                                @RequestBody(required = false) Decision d) {
        return catalogue.decideException(users.require(), modelCode, id, true, d == null ? null : d.note());
    }

    @PostMapping("/models/{modelCode}/posture/exceptions/{id}/reject")
    @PreAuthorize(Spel.SECOPS)
    public Map<String, Object> rejectException(@PathVariable @Pattern(regexp = Spel.MODEL_CODE) String modelCode, @PathVariable UUID id,
                                               @RequestBody(required = false) Decision d) {
        return catalogue.decideException(users.require(), modelCode, id, false, d == null ? null : d.note());
    }

    @GetMapping("/compare")
    @PreAuthorize(Spel.CATALOGUE_READ)
    public List<Map<String, Object>> compare(@RequestParam @Pattern(regexp = "(MD-[0-9]{3}|WZ-[0-9]{6})(,(MD-[0-9]{3}|WZ-[0-9]{6})){0,5}") String models) {
        return catalogue.compare(Arrays.asList(models.split(",")));
    }

    @GetMapping("/options")
    @PreAuthorize(Spel.CATALOGUE_READ)
    public List<Map<String, Object>> options() {
        return packs.all();
    }

    @GetMapping("/options/{option}")
    @PreAuthorize(Spel.CATALOGUE_READ)
    public Map<String, Object> option(@PathVariable @Pattern(regexp = "P([1-9]|10)") String option) {
        return packs.view(option);
    }

    @GetMapping("/stations")
    @PreAuthorize(Spel.CATALOGUE_READ)
    public List<Map<String, Object>> stations(@RequestParam(required = false) @Pattern(regexp = Spel.SITE) String siteCode) {
        return stations.all().stream().filter(s -> siteCode == null || siteCode.equals(s.siteCode())).map(StationStore::view).toList();
    }

    // ------------------------------------------------------------------ driver registry

    @GetMapping("/drivers")
    @PreAuthorize(Spel.DEVICE_ROLES)
    public List<Map<String, Object>> drivers(@RequestParam(required = false) @Pattern(regexp = Spel.MODEL_CODE) String modelCode,
                                             @RequestParam(required = false) @Pattern(regexp = Spel.CODE) String status) {
        return registry.list(modelCode, status);
    }

    @GetMapping("/drivers/matrix")
    @PreAuthorize(Spel.DEVICE_ROLES)
    public List<Map<String, Object>> matrix(@RequestParam(required = false) @Pattern(regexp = Spel.MODEL_CODE) String modelCode) {
        return registry.matrix(modelCode);
    }

    @GetMapping("/drivers/{packageId}/{version}")
    @PreAuthorize(Spel.DEVICE_ROLES)
    public Map<String, Object> driver(@PathVariable @Pattern(regexp = Spel.PACKAGE_ID) String packageId,
                                      @PathVariable @Pattern(regexp = Spel.VERSION) String version) {
        return registry.get(packageId, version);
    }

    @PostMapping("/drivers/validate")
    @PreAuthorize(Spel.DEVICE_ROLES)
    public Map<String, Object> validate(@RequestBody Map<String, Object> pkg) {
        DriverPackageRules.Report r = registry.validate(pkg);
        return Docs.of("valid", r.valid(), "sha256", r.sha256(), "errors", r.errors(), "warnings", r.warnings());
    }

    @PostMapping("/drivers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.TESTER)
    public Map<String, Object> register(@RequestBody DriverRegistryService.RegisterCommand cmd) {
        return registry.register(users.require(), cmd);
    }

    @PostMapping("/drivers/{packageId}/{version}/certify")
    @PreAuthorize(Spel.DRIVER_CERTIFY)
    public Map<String, Object> certify(@PathVariable @Pattern(regexp = Spel.PACKAGE_ID) String packageId,
                                       @PathVariable @Pattern(regexp = Spel.VERSION) String version) {
        return registry.certify(users.require(), packageId, version);
    }

    public record ReasonCommand(String reason) {
    }

    @PostMapping("/drivers/{packageId}/{version}/deprecate")
    @PreAuthorize(Spel.DRIVER_DEPRECATE)
    public Map<String, Object> deprecate(@PathVariable @Pattern(regexp = Spel.PACKAGE_ID) String packageId,
                                         @PathVariable @Pattern(regexp = Spel.VERSION) String version, @RequestBody(required = false) ReasonCommand cmd) {
        return registry.deprecate(users.require(), packageId, version, cmd == null ? null : cmd.reason());
    }

    @PostMapping("/drivers/{packageId}/{version}/revoke")
    @PreAuthorize(Spel.SECOPS)
    public Map<String, Object> revoke(@PathVariable @Pattern(regexp = Spel.PACKAGE_ID) String packageId,
                                      @PathVariable @Pattern(regexp = Spel.VERSION) String version, @RequestBody(required = false) ReasonCommand cmd) {
        return registry.revoke(users.require(), packageId, version, cmd == null ? null : cmd.reason());
    }
}
