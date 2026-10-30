package com.wellnessz.e11.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e11.advisory.HealthAdvisoryService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E11-F05 destination / base health advisories with acknowledgement tracking. */
@RestController("e11AdvisoryController")
@RequestMapping("/api/v1")
public class AdvisoryController {

    private final HealthAdvisoryService advisories;
    private final CurrentUserProvider users;

    public AdvisoryController(HealthAdvisoryService advisories, CurrentUserProvider users) {
        this.advisories = advisories;
        this.users = users;
    }

    @PostMapping("/advisories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.ADVISORY_WRITE)
    public HealthAdvisoryService.AdvisoryView publish(@Valid @RequestBody HealthAdvisoryService.AdvisoryCommand cmd) {
        return advisories.publish(users.require(), cmd);
    }

    @GetMapping("/advisories")
    @PreAuthorize(Spel.OH)
    public List<HealthAdvisoryService.AdvisoryView> list() {
        return advisories.list();
    }

    @GetMapping("/advisories/{id}")
    @PreAuthorize(Spel.OH)
    public HealthAdvisoryService.AdvisoryView get(@PathVariable UUID id) {
        return advisories.get(id);
    }

    @GetMapping("/advisories/{id}/acknowledgements")
    @PreAuthorize(Spel.OH)
    public HealthAdvisoryService.AcknowledgementView acknowledgements(@PathVariable UUID id) {
        return advisories.acknowledgements(id);
    }

    @GetMapping("/me/advisories")
    @PreAuthorize(Spel.EMPLOYEE)
    public List<HealthAdvisoryService.MyAdvisory> mine() {
        return advisories.mine(TravelController.employee(users.require()));
    }

    @PostMapping("/me/advisories/{id}/acknowledge")
    @PreAuthorize(Spel.EMPLOYEE)
    public HealthAdvisoryService.MyAdvisory acknowledge(@PathVariable UUID id) {
        return advisories.acknowledge(TravelController.employee(users.require()), id);
    }
}
