package com.wellnessz.casekernel.web;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.AttachmentMeta;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseLink;
import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseViews;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1")
public class CaseController {

    private final CaseService service;
    private final CaseWorkService work;
    private final CurrentUserProvider users;
    private final ModuleRegistry modules;

    public CaseController(CaseService service, CaseWorkService work, CurrentUserProvider users, ModuleRegistry modules) {
        this.service = service;
        this.work = work;
        this.users = users;
        this.modules = modules;
    }

    public record SelfReferralRequest(@NotNull CaseCategory category, @NotBlank @Size(max = 4000) String description,
                                      @Size(max = 20) String preferredContact) {
    }

    public record TransitionRequest(@NotNull CaseStatus status) {
    }

    public record FitnessRequest(@NotNull FitnessStatus fitnessStatus, LocalDate restrictionUntil) {
    }

    public record LinkRequest(@NotNull CaseLink.Type type, @NotBlank @Size(max = 200) String ref) {
    }

    public record TaskRequest(@NotNull CaseTask.Type type, @NotBlank @Size(max = 300) String title, Instant dueAt,
                              @Size(max = 200) String assignee) {
    }

    @GetMapping("/cases")
    public List<Object> list(@RequestParam(defaultValue = "100") int limit) {
        return service.list(users.require(), limit);
    }

    @GetMapping("/cases/{id}")
    public Object get(@PathVariable UUID id) {
        return service.get(users.require(), id);
    }

    @GetMapping("/cases/{id}/status")
    public CaseViews.StatusOnly status(@PathVariable UUID id) {
        return service.status(users.require(), id);
    }

    /** Base-framework self-referral; the E01 form is {@code POST /intake/referrals/self}. */
    @PostMapping("/cases/self-referral")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseViews.Self selfReferral(@Valid @RequestBody SelfReferralRequest r) {
        return service.selfReferral(users.require(), r.category(), r.description());
    }

    @PostMapping("/cases/{id}/transition")
    public CaseViews.StatusOnly transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest r) {
        return service.transition(users.require(), id, r.status());
    }

    @PostMapping("/cases/{id}/fitness")
    public CaseViews.StatusOnly fitness(@PathVariable UUID id, @Valid @RequestBody FitnessRequest r) {
        return service.recordFitness(users.require(), id, r.fitnessStatus(), r.restrictionUntil());
    }

    @PostMapping("/cases/{id}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseViews.LinkView link(@PathVariable UUID id, @Valid @RequestBody LinkRequest r) {
        return service.link(users.require(), id, r.type(), r.ref());
    }

    @GetMapping("/cases/{id}/timeline")
    public List<CaseWorkService.TimelineEntry> timeline(@PathVariable UUID id) {
        return work.timeline(users.require(), id);
    }

    @GetMapping("/cases/{id}/tasks")
    public List<CaseWorkService.TaskView> tasks(@PathVariable UUID id) {
        return work.tasks(users.require(), id);
    }

    @PostMapping("/cases/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseWorkService.TaskView createTask(@PathVariable UUID id, @Valid @RequestBody TaskRequest r) {
        return work.createTask(users.require(), id, r.type(), r.title(), r.dueAt(), r.assignee());
    }

    @PostMapping("/tasks/{taskId}/complete")
    public CaseWorkService.TaskView completeTask(@PathVariable UUID taskId) {
        return work.completeTask(users.require(), taskId);
    }

    @PostMapping(value = "/cases/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentMeta upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        return work.upload(users.require(), id, file);
    }

    @GetMapping("/cases/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        CaseWorkService.Download d = work.download(users.require(), id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(d.content());
    }

    @GetMapping("/modules")
    public List<ModuleRegistry.ModuleInfo> modules() {
        return modules.all();
    }
}
