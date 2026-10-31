package com.wellnessz.e32.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import com.wellnessz.e32.config.CertificationService;
import com.wellnessz.e32.config.ConfigurationService;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E32-F02 / F06 / F10 / F11: the provisioning wizard, certification, publication, clone / revise and decommissioning (contract §13.2, §13.5, §13.11). */
@RestController("e32ConfigurationController")
@RequestMapping("/api/v1/device-configurations")
public class ConfigurationController {

    private final ConfigurationService configurations;
    private final CertificationService certifications;
    private final CurrentUserProvider users;
    private final E32Support support;

    public ConfigurationController(ConfigurationService configurations, CertificationService certifications, CurrentUserProvider users,
                                   E32Support support) {
        this.configurations = configurations;
        this.certifications = certifications;
        this.users = users;
        this.support = support;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> create(@RequestBody ConfigurationService.CreateCommand cmd) {
        return configurations.create(users.require(), cmd);
    }

    @GetMapping
    @PreAuthorize(Spel.CONFIG_READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) @Pattern(regexp = Spel.CODE) String status,
                                          @RequestParam(required = false) @Pattern(regexp = Spel.SITE) String siteCode,
                                          @RequestParam(required = false) @Pattern(regexp = Spel.MODEL_CODE) String modelCode) {
        return configurations.list(status, siteCode, modelCode);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Spel.CONFIG_READ)
    public Map<String, Object> get(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.get(id);
    }

    @GetMapping("/{id}/options")
    @PreAuthorize(Spel.CONFIG_READ)
    public List<Map<String, Object>> options(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.options(id);
    }

    @PutMapping("/{id}/steps/{n}")
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> step(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @PathVariable @Min(1) @Max(8) int n,
                                    @RequestBody Map<String, Object> body) {
        return configurations.putStep(users.require(), id, n, body);
    }

    @PostMapping("/{id}/steps/3/auto-check")
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> autoCheck(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.autoCheck(users.require(), id);
    }

    @GetMapping("/{id}/steps/4/defaults")
    @PreAuthorize(Spel.CONFIG_READ)
    public Map<String, Object> defaults(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.defaults(id);
    }

    public record PreviewCommand(String datasetId) {
    }

    @PostMapping("/{id}/steps/5/preview")
    @PreAuthorize(Spel.CONFIG_READ)
    public Map<String, Object> preview(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @RequestBody(required = false) PreviewCommand cmd) {
        return configurations.preview(id, cmd == null ? null : cmd.datasetId());
    }

    @PostMapping("/{id}/steps/6/tests")
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> test(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @RequestBody ConfigurationService.TestCommand cmd) {
        return configurations.runTest(users.require(), id, cmd);
    }

    @PostMapping("/{id}/steps/6/nurse-review")
    @PreAuthorize(Spel.NURSE_REVIEW)
    public Map<String, Object> nurseReview(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @RequestBody ConfigurationService.ReviewCommand cmd) {
        return configurations.nurseReview(users.require(), id, cmd);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> submit(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.submit(users.require(), id);
    }

    public record NoteCommand(String note, String reason) {
    }

    /** Reachable by DEVICE_ADMIN too, so that a self-approval answers 409 four_eyes_violation rather than 403. */
    @PostMapping("/{id}/approve")
    @PreAuthorize(Spel.APPROVE)
    public Map<String, Object> approve(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @RequestBody(required = false) NoteCommand cmd) {
        return configurations.approve(users.require(), id, cmd == null ? null : cmd.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Spel.APPROVER)
    public Map<String, Object> reject(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @RequestBody NoteCommand cmd) {
        return configurations.reject(users.require(), id, cmd.reason() == null ? cmd.note() : cmd.reason());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> publish(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.publish(users.require(), id);
    }

    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> cloneTo(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @RequestBody ConfigurationService.CloneCommand cmd) {
        return configurations.cloneTo(users.require(), id, cmd);
    }

    @PostMapping("/{id}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> revise(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.revise(users.require(), id);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> pause(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.pause(users.require(), id, true);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize(Spel.CONFIG_WRITE)
    public Map<String, Object> resume(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.pause(users.require(), id, false);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> suspend(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @RequestBody(required = false) NoteCommand cmd) {
        return configurations.suspend(users.require(), id, cmd == null ? null : cmd.reason(), true);
    }

    @PostMapping("/{id}/resume-service")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> resumeService(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.suspend(users.require(), id, null, false);
    }

    @PostMapping("/{id}/decommission")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> decommission(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id,
                                            @RequestBody ConfigurationService.DecommissionCommand cmd) {
        return configurations.requestDecommission(users.require(), id, cmd);
    }

    @PostMapping("/{id}/decommission/approve")
    @PreAuthorize(Spel.APPROVE)
    public Map<String, Object> approveDecommission(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @RequestBody(required = false) NoteCommand cmd) {
        return configurations.decideDecommission(users.require(), id, true, cmd == null ? null : cmd.note());
    }

    @PostMapping("/{id}/decommission/reject")
    @PreAuthorize(Spel.APPROVER)
    public Map<String, Object> rejectDecommission(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id, @RequestBody(required = false) NoteCommand cmd) {
        return configurations.decideDecommission(users.require(), id, false, cmd == null ? null : cmd.note());
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(Spel.CHANGES)
    public List<Map<String, Object>> history(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.history(id);
    }

    @GetMapping("/{id}/data-flow")
    @PreAuthorize(Spel.CERTIFICATE)
    public Map<String, Object> dataFlow(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        return configurations.dataFlow(id);
    }

    @GetMapping("/{id}/certification")
    @PreAuthorize(Spel.CERTIFICATE)
    public Map<String, Object> certification(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        support.requireEnabled();
        return certifications.record(id);
    }

    @GetMapping("/{id}/certificate.pdf")
    @PreAuthorize(Spel.CERTIFICATE)
    public ResponseEntity<byte[]> certificate(@PathVariable @Pattern(regexp = Spel.CONFIG_ID) String id) {
        support.requireEnabled();
        byte[] pdf = certifications.pdf(id);
        support.audit("DEVICE_CERTIFICATE_PDF", "DEVICE_CONFIGURATION", id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(id + "-certificate.pdf").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(pdf);
    }
}
