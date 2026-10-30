package com.wellnessz.e11.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e11.exposure.ExposurePathwayService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E11-F03 exposure pathways with protocol timers, the chemical register and task completion. */
@RestController("e11ExposureController")
@RequestMapping("/api/v1")
public class ExposureController {

    private final ExposurePathwayService exposures;
    private final CurrentUserProvider users;

    public ExposureController(ExposurePathwayService exposures, CurrentUserProvider users) {
        this.exposures = exposures;
        this.users = users;
    }

    @GetMapping("/exposures/pathways")
    @PreAuthorize(Spel.OH)
    public List<ExposurePathwayService.PathwayView> pathways() {
        return exposures.pathways();
    }

    @GetMapping("/exposures/chemicals")
    @PreAuthorize(Spel.OH)
    public List<ExposurePathwayService.ChemicalView> chemicals() {
        return exposures.chemicals();
    }

    @PostMapping("/exposures")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public ExposurePathwayService.ExposureView create(@Valid @RequestBody ExposurePathwayService.CreateCommand cmd) {
        return exposures.create(users.require(), cmd);
    }

    @GetMapping("/exposures")
    @PreAuthorize(Spel.CLINICIANS)
    public List<ExposurePathwayService.ExposureView> list(@RequestParam(required = false) @Pattern(regexp = "OPEN|OVERDUE|CLOSED|COMPLETED") String status) {
        return exposures.list(users.require(), "COMPLETED".equals(status) ? "CLOSED" : status);
    }

    @GetMapping("/exposures/{id}")
    @PreAuthorize(Spel.CLINICIANS)
    public ExposurePathwayService.ExposureView get(@PathVariable UUID id) {
        return exposures.get(users.require(), id);
    }

    @PostMapping("/exposures/{id}/tasks/{code}/complete")
    @PreAuthorize(Spel.CLINICIANS)
    public ExposurePathwayService.ExposureView complete(@PathVariable UUID id, @PathVariable @Pattern(regexp = Spel.CODE) String code,
                                                        @Valid @RequestBody(required = false) ExposurePathwayService.CompleteCommand cmd) {
        return exposures.complete(users.require(), id, code, cmd);
    }
}
