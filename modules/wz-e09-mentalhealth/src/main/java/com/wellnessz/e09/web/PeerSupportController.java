package com.wellnessz.e09.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

import com.wellnessz.e09.model.MentalHealthEnums.PeerCategory;
import com.wellnessz.e09.model.MentalHealthEnums.PeerOutcome;
import com.wellnessz.e09.peer.PeerSupportService;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Flight-crew peer support programme (contract §3.3): volunteer register (coordinator), the volunteer's own pseudonymous requests
 * and contact log, referral to the trusted professional and the quarterly anonymised SMS report. No view carries an employee id.
 */
@RestController("e09PeerSupportController")
@RequestMapping("/api/v1/peer-support")
public class PeerSupportController {

    private final PeerSupportService peers;
    private final CurrentUserProvider users;

    public PeerSupportController(PeerSupportService peers, CurrentUserProvider users) {
        this.peers = peers;
        this.users = users;
    }

    public record TrainingBody(@NotBlank @Size(max = 200) String course, @NotNull LocalDate completedOn, LocalDate expiresOn) {
    }

    public record VolunteerBody(@NotBlank @Pattern(regexp = Patterns.USERNAME) String username, @NotBlank @Size(max = 200) String displayName,
                                @Pattern(regexp = "|" + Patterns.EMAIL) String email, @NotBlank @Pattern(regexp = Patterns.BASE) String base,
                                @Size(max = 10) List<@Pattern(regexp = Patterns.LANGUAGE) String> languages, boolean available,
                                @Size(max = 30) List<@Valid @NotNull TrainingBody> trainingRecords) {
        PeerSupportService.VolunteerCommand command() {
            return new PeerSupportService.VolunteerCommand(username, displayName, email, base, languages, available, trainingRecords == null ? null
                    : trainingRecords.stream().map(t -> new PeerSupportService.TrainingRecord(t.course(), t.completedOn(), t.expiresOn())).toList());
        }
    }

    @GetMapping("/volunteers")
    @PreAuthorize("hasRole('PEER_SUPPORT_COORDINATOR')")
    public List<PeerSupportService.VolunteerView> volunteers() {
        return peers.volunteers();
    }

    @GetMapping("/volunteers/{id}")
    @PreAuthorize("hasRole('PEER_SUPPORT_COORDINATOR')")
    public PeerSupportService.VolunteerView volunteer(@PathVariable UUID id) {
        return peers.volunteer(id);
    }

    @PostMapping("/volunteers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PEER_SUPPORT_COORDINATOR')")
    public PeerSupportService.VolunteerView create(@Valid @RequestBody VolunteerBody b) {
        return peers.createVolunteer(users.require(), b.command());
    }

    @PutMapping("/volunteers/{id}")
    @PreAuthorize("hasRole('PEER_SUPPORT_COORDINATOR')")
    public PeerSupportService.VolunteerView update(@PathVariable UUID id, @Valid @RequestBody VolunteerBody b) {
        return peers.updateVolunteer(users.require(), id, b.command());
    }

    @GetMapping("/my-requests")
    @PreAuthorize("hasRole('PEER_SUPPORT_VOLUNTEER')")
    public List<PeerSupportService.RequestView> myRequests() {
        return peers.myRequests(users.require());
    }

    public record ContactBody(@Pattern(regexp = "|" + Patterns.CONTACT_CODE) String contactCode, @NotNull LocalDate date,
                              @Min(1) @Max(600) int durationMinutes, @NotBlank @Pattern(regexp = Patterns.PEER_CATEGORY) String category,
                              @NotBlank @Pattern(regexp = Patterns.PEER_OUTCOME) String outcome) {
    }

    @PostMapping("/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PEER_SUPPORT_VOLUNTEER')")
    public PeerSupportService.ContactView logContact(@Valid @RequestBody ContactBody b) {
        return peers.logContact(users.require(), new PeerSupportService.ContactCommand(b.contactCode(), b.date(), b.durationMinutes(),
                PeerCategory.valueOf(b.category()), PeerOutcome.valueOf(b.outcome())));
    }

    @GetMapping("/contacts")
    @PreAuthorize("hasRole('PEER_SUPPORT_VOLUNTEER')")
    public List<PeerSupportService.ContactView> myContacts() {
        return peers.myContacts(users.require());
    }

    @PostMapping("/contacts/{id}/refer")
    @PreAuthorize("hasRole('PEER_SUPPORT_VOLUNTEER')")
    public PeerSupportService.ContactView refer(@PathVariable UUID id) {
        return peers.refer(users.require(), id);
    }

    @GetMapping("/reports/quarterly")
    @PreAuthorize("hasAnyRole('SAFETY_MANAGER','PEER_SUPPORT_COORDINATOR','CMO')")
    public PeerSupportService.QuarterlyReport quarterly(@RequestParam(required = false) @Pattern(regexp = Patterns.QUARTER) String quarter) {
        return peers.quarterly(users.require(), quarter);
    }

    public record SendBody(@Pattern(regexp = Patterns.QUARTER) String quarter) {
    }

    @PostMapping("/reports/quarterly/send")
    @PreAuthorize("hasAnyRole('SAFETY_MANAGER','PEER_SUPPORT_COORDINATOR')")
    public PeerSupportService.SendResult send(@RequestParam(required = false) @Pattern(regexp = Patterns.QUARTER) String quarter,
                                              @Valid @RequestBody(required = false) SendBody body) {
        return peers.send(users.require(), quarter != null ? quarter : body == null ? null : body.quarter());
    }
}
