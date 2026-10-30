package com.wellnessz.e11.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

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

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e11.publichealth.PublicHealthReportService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E11-F06 public-health / authority reports: generation, submission tracking and the correspondence log. */
@RestController("e11PublicHealthController")
@RequestMapping("/api/v1")
public class PublicHealthController {

    private final PublicHealthReportService reports;
    private final CurrentUserProvider users;

    public PublicHealthController(PublicHealthReportService reports, CurrentUserProvider users) {
        this.reports = reports;
        this.users = users;
    }

    @PostMapping("/public-health/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.PUBLIC_HEALTH)
    public PublicHealthReportService.ReportView create(@Valid @RequestBody PublicHealthReportService.CreateCommand cmd) {
        return reports.create(users.require(), cmd);
    }

    @PostMapping("/public-health/reports/{id}/submit")
    @PreAuthorize(Spel.PUBLIC_HEALTH)
    public PublicHealthReportService.ReportView submit(@PathVariable UUID id, @Valid @RequestBody PublicHealthReportService.SubmitCommand cmd) {
        return reports.submit(users.require(), id, cmd);
    }

    @PostMapping(path = "/public-health/reports/{id}/correspondence", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.PUBLIC_HEALTH)
    public PublicHealthReportService.ReportView correspondence(@PathVariable UUID id,
                                                               @Valid @RequestBody PublicHealthReportService.CorrespondenceCommand cmd) {
        return reports.correspondence(users.require(), id, cmd);
    }

    /** Authority letter (PDF / image) stored as {@code AUTHORITY_CORRESPONDENCE} and logged on the report. */
    @PostMapping(path = "/public-health/reports/{id}/correspondence/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.PUBLIC_HEALTH)
    public PublicHealthReportService.ReportView upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                                       @RequestParam @Pattern(regexp = "IN|OUT") String direction, @RequestParam String summary) {
        try {
            return reports.upload(users.require(), id, direction, summary, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw ApiException.badRequest("unreadable_file", "The file could not be read");
        }
    }

    @GetMapping("/public-health/reports")
    @PreAuthorize(Spel.OH)
    public List<PublicHealthReportService.ReportView> list(@RequestParam(required = false) @Pattern(regexp = "PENDING|OVERDUE|SUBMITTED") String status) {
        return reports.list(users.require(), status);
    }

    @GetMapping("/public-health/reports/{id}")
    @PreAuthorize(Spel.OH)
    public PublicHealthReportService.ReportView get(@PathVariable UUID id) {
        return reports.get(users.require(), id);
    }
}
