package com.wellnessz.e33.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e33.domain.Comparison;
import com.wellnessz.e33.domain.FallbackChain;
import com.wellnessz.e33.service.BatchService;
import com.wellnessz.e33.service.CaptureKpiService;
import com.wellnessz.e33.service.CaptureSessionService;
import com.wellnessz.e33.service.CaptureSupport;
import com.wellnessz.e33.service.CaptureViews;
import com.wellnessz.e33.service.DeviceListService;
import com.wellnessz.e33.service.FormRegistry;
import com.wellnessz.security.identity.CurrentUserProvider;

/** {@code /device-capture/**} – the form-binding and capture API of the OH workspace (contract 14.2–14.7). */
@RestController
@RequestMapping("/api/v1/device-capture")
public class CaptureController {

    private final FormRegistry forms;
    private final DeviceListService devices;
    private final CaptureSessionService sessions;
    private final BatchService batches;
    private final CaptureKpiService kpis;
    private final CaptureSupport support;
    private final CurrentUserProvider users;

    public CaptureController(FormRegistry forms, DeviceListService devices, CaptureSessionService sessions, BatchService batches,
                             CaptureKpiService kpis, CaptureSupport support, CurrentUserProvider users) {
        this.forms = forms;
        this.devices = devices;
        this.sessions = sessions;
        this.batches = batches;
        this.kpis = kpis;
        this.support = support;
        this.users = users;
    }

    // ------------------------------------------------------------------ requests

    public record OpenRequest(@NotBlank @Pattern(regexp = Spel.CODE) String formCode, @Pattern(regexp = "[0-9]{1,3}") String formVersion,
                              @Pattern(regexp = "[A-Za-z0-9_\\-]{1,60}") String fieldGroup, @NotBlank @Pattern(regexp = Spel.ID) String employeeId,
                              UUID caseId, @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9:_\\-]{1,80}") String orderRef,
                              @Pattern(regexp = Spel.ID) String deviceInstanceId, @NotBlank @Pattern(regexp = Spel.ID) String siteCode,
                              @NotBlank @Pattern(regexp = Spel.ID) String stationCode) {
    }

    public record AcceptRequest(@Size(max = 30) List<@Pattern(regexp = "[A-Za-z0-9_:\\-]{1,80}") String> acknowledgedFlags,
                                @Size(max = 2000) String note) {
    }

    public record RejectRequest(@NotBlank @Pattern(regexp = "RETEST|WRONG_EMPLOYEE|ARTEFACT|DEVICE_FAULT|OTHER") String reasonCode,
                                @Size(max = 2000) String note) {
    }

    public record Attested(@NotBlank @Size(max = 120) String model, @NotBlank @Size(max = 80) String serial,
                           @NotBlank @Size(max = 80) String calibrationId, @NotNull LocalDate calibrationDate) {
    }

    public record FallbackRequest(@NotBlank @Pattern(regexp = "ALTERNATE_DEVICE|OCR_VERIFIED|MANUAL_ATTESTED") String path,
                                  @NotBlank @Pattern(regexp = "DEVICE_UNAVAILABLE|DEVICE_LOCKED|CONNECTION_FAILED|DISPLAY_ONLY|OTHER") String reasonCode,
                                  @Pattern(regexp = Spel.ID) String deviceInstanceId, UUID imageArchiveId,
                                  @Size(max = 60) Map<@Pattern(regexp = "[A-Za-z0-9_.\\-]{1,60}") String, Object> values,
                                  @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal ocrConfidence, @Valid Attested attested) {
    }

    public record SignOffRequest(@Size(max = 2000) String note) {
    }

    public record AnnotationRequest(@NotBlank @Size(max = 2000) String text) {
    }

    public record BatchRequest(@NotBlank @Pattern(regexp = Spel.ID) String siteCode, @Pattern(regexp = "[A-Za-z0-9:_\\-]{1,80}") String screeningEventId,
                               @NotBlank @Pattern(regexp = Spel.CODE) String formCode,
                               @NotNull @Size(min = 1, max = 10) List<@Pattern(regexp = Spel.ID) String> deviceInstanceIds,
                               @Size(max = 300) List<@Pattern(regexp = Spel.ID) String> employeeIds,
                               @Size(max = 300) List<@Pattern(regexp = Spel.ID) String> badgeIds) {
    }

    public record AcceptAllRequest(@Size(max = 300) List<UUID> sessionIds, Map<String, List<String>> acknowledgedFlags) {
    }

    // ------------------------------------------------------------------ form registry and device list

    @GetMapping("/forms")
    @PreAuthorize(Spel.FORMS)
    public List<FormRegistry.Form> forms() {
        support.requireEnabled();
        return forms.forms();
    }

    @GetMapping("/forms/{formCode}")
    @PreAuthorize(Spel.FORMS)
    public FormRegistry.Form form(@PathVariable @Pattern(regexp = Spel.CODE) String formCode) {
        support.requireEnabled();
        return forms.require(formCode);
    }

    @GetMapping("/devices")
    @PreAuthorize(Spel.CAPTURE)
    public List<DeviceListService.DeviceChoice> devices(@RequestParam @Pattern(regexp = Spel.CODE) String formCode,
                                                        @RequestParam @Pattern(regexp = Spel.ID) String siteCode,
                                                        @RequestParam @Pattern(regexp = Spel.ID) String stationCode) {
        support.requireEnabled();
        return devices.devices(formCode, siteCode, stationCode);
    }

    // ------------------------------------------------------------------ sessions

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CAPTURE)
    public CaptureViews.SessionView open(@Valid @RequestBody OpenRequest r) {
        return sessions.open(users.require(), new CaptureSessionService.OpenCommand(r.formCode(), r.formVersion(), r.fieldGroup(), r.employeeId(),
                r.caseId(), r.orderRef(), r.deviceInstanceId(), r.siteCode(), r.stationCode()));
    }

    @GetMapping("/sessions/{id}")
    @PreAuthorize(Spel.SESSION_READ)
    public CaptureViews.SessionView get(@PathVariable UUID id) {
        return sessions.get(users.require(), id);
    }

    @GetMapping(path = "/sessions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize(Spel.SESSION_READ)
    public SseEmitter events(@PathVariable UUID id) {
        return sessions.events(users.require(), id);
    }

    @PostMapping("/sessions/{id}/accept")
    @PreAuthorize(Spel.SESSION_READ)
    public CaptureSessionService.AcceptResult accept(@PathVariable UUID id, @Valid @RequestBody(required = false) AcceptRequest r) {
        AcceptRequest req = r == null ? new AcceptRequest(List.of(), null) : r;
        return sessions.accept(users.require(), id, new CaptureSessionService.AcceptCommand(req.acknowledgedFlags(), req.note()));
    }

    @PostMapping("/sessions/{id}/reject")
    @PreAuthorize(Spel.SESSION_READ)
    public CaptureViews.SessionView reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest r) {
        return sessions.reject(users.require(), id, new CaptureSessionService.RejectCommand(r.reasonCode(), r.note()));
    }

    @PostMapping("/sessions/{id}/cancel")
    @PreAuthorize(Spel.CAPTURE)
    public CaptureViews.SessionView cancel(@PathVariable UUID id) {
        return sessions.cancel(users.require(), id);
    }

    @PostMapping("/sessions/{id}/fallback")
    @PreAuthorize(Spel.CAPTURE)
    public CaptureViews.SessionView fallback(@PathVariable UUID id, @Valid @RequestBody FallbackRequest r) {
        FallbackChain.Attestation a = r.attested() == null ? null
                : new FallbackChain.Attestation(r.attested().model(), r.attested().serial(), r.attested().calibrationId(), r.attested().calibrationDate());
        return sessions.fallback(users.require(), id, new CaptureSessionService.FallbackCommand(r.path(), r.reasonCode(), r.deviceInstanceId(),
                r.imageArchiveId(), r.values() == null ? Map.of() : r.values(), r.ocrConfidence(), a));
    }

    @PostMapping("/sessions/{id}/sign-off")
    @PreAuthorize(Spel.PHYSICIANS)
    public CaptureViews.SessionView signOff(@PathVariable UUID id, @Valid @RequestBody(required = false) SignOffRequest r) {
        return sessions.signOff(users.require(), id, r == null ? null : r.note());
    }

    @GetMapping("/sign-offs")
    @PreAuthorize(Spel.PHYSICIANS)
    public List<CaptureSessionService.SignOffItem> signOffs(@RequestParam(required = false) @Pattern(regexp = Spel.ID) String siteCode) {
        return sessions.pendingSignOffs(users.require(), siteCode);
    }

    @PostMapping("/sessions/{id}/annotations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.SESSION_READ)
    public CaptureViews.AnnotationView annotate(@PathVariable UUID id, @Valid @RequestBody AnnotationRequest r) {
        return sessions.annotate(users.require(), id, r.text());
    }

    // ------------------------------------------------------------------ OCR and comparison

    @PostMapping(path = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public CaptureSessionService.OcrResult ocr(@RequestPart("image") MultipartFile image,
                                               @RequestParam(required = false) @Pattern(regexp = Spel.ID) String deviceInstanceId,
                                               @RequestParam(required = false) @Pattern(regexp = "MD-[0-9]{3,4}") String modelCode,
                                               @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{2,30}") String category) {
        if (image.getSize() > 10L * 1024 * 1024) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "Images up to 10 MB are accepted");
        }
        try {
            return sessions.ocr(users.require(), image.getBytes(), image.getContentType(), deviceInstanceId, modelCode, category);
        } catch (IOException e) {
            throw ApiException.badRequest("validation_failed", "The image could not be read");
        }
    }

    @GetMapping("/employees/{employeeId}/summary")
    @PreAuthorize(Spel.CLINICIANS)
    public Comparison.Summary summary(@PathVariable @Pattern(regexp = Spel.ID) String employeeId,
                                      @RequestParam @Pattern(regexp = "AUDIOMETRY|SPIROMETRY|VITALS|BODY_COMPOSITION") String category) {
        return sessions.summary(users.require(), employeeId, category);
    }

    // ------------------------------------------------------------------ batch mode

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public BatchService.BatchView createBatch(@Valid @RequestBody BatchRequest r) {
        return batches.create(users.require(), new BatchService.CreateCommand(r.siteCode(), r.screeningEventId(), r.formCode(),
                r.deviceInstanceIds(), r.employeeIds(), r.badgeIds()));
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize(Spel.CLINICIANS)
    public BatchService.BatchView batch(@PathVariable UUID id) {
        return batches.get(users.require(), id);
    }

    @PostMapping("/batches/{id}/next")
    @PreAuthorize(Spel.CLINICIANS)
    public BatchService.BatchView next(@PathVariable UUID id) {
        return batches.next(users.require(), id);
    }

    @PostMapping("/batches/{id}/accept-all")
    @PreAuthorize(Spel.CLINICIANS)
    public BatchService.AcceptAllResult acceptAll(@PathVariable UUID id, @Valid @RequestBody(required = false) AcceptAllRequest r) {
        return batches.acceptAll(users.require(), id, r == null ? List.of() : r.sessionIds(), r == null ? Map.of() : r.acknowledgedFlags());
    }

    // ------------------------------------------------------------------ KPIs

    @GetMapping("/kpis")
    @PreAuthorize(Spel.KPIS)
    public CaptureKpiService.KpiView kpis(@RequestParam(required = false) @Pattern(regexp = Spel.ID) String siteCode,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return kpis.kpis(siteCode, from, to);
    }
}
