package com.wellnessz.e09.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e09.casework.PathwayService;
import com.wellnessz.e09.casework.SupportCaseService;
import com.wellnessz.e09.casework.SupportViews;
import com.wellnessz.e09.cism.CismService;
import com.wellnessz.e09.model.MentalHealthEnums.Concern;
import com.wellnessz.e09.model.MentalHealthEnums.Instrument;
import com.wellnessz.e09.model.MentalHealthEnums.PreferredContact;
import com.wellnessz.e09.model.MentalHealthEnums.Sharing;
import com.wellnessz.e09.peer.PeerSupportService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Employee app (contract §3.1 sharing, §3.2 self-referral & screens, §3.3 peer contact, §3.4 CISM offers). */
@RestController("e09MeSupportController")
@RequestMapping("/api/v1/me")
public class MeSupportController {

    private final PathwayService pathway;
    private final SupportCaseService cases;
    private final PeerSupportService peers;
    private final CismService cism;
    private final CurrentUserProvider users;

    public MeSupportController(PathwayService pathway, SupportCaseService cases, PeerSupportService peers, CismService cism,
                               CurrentUserProvider users) {
        this.pathway = pathway;
        this.cases = cases;
        this.peers = peers;
        this.cism = cism;
        this.users = users;
    }

    @GetMapping("/support/signposting")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public SupportViews.Signposting signposting() {
        return pathway.signposting();
    }

    public record SelfReferralBody(@NotBlank @Pattern(regexp = Patterns.CONTACT) String preferredContact, @Pattern(regexp = "|" + Patterns.PHONE) String phone,
                                   @Size(max = 100) String bestTime, @NotBlank @Pattern(regexp = Patterns.CONCERN) String concern, @Size(max = 2000) String message) {
    }

    @PostMapping("/support/self-referral")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EMPLOYEE')")
    public SupportViews.SelfReferralResult selfReferral(@Valid @RequestBody SelfReferralBody b) {
        return pathway.selfReferral(users.require(), new PathwayService.SelfReferral(PreferredContact.valueOf(b.preferredContact()), b.phone(), b.bestTime(),
                Concern.valueOf(b.concern()),
                b.message()));
    }

    @GetMapping("/support/screens/{instrument}")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public SupportViews.ScreenDefinition screenDefinition(@PathVariable Instrument instrument) {
        return pathway.definition(instrument);
    }

    public record ScreenBody(@NotBlank @Pattern(regexp = Patterns.INSTRUMENT) String instrument, @NotEmpty @Size(max = 9) List<@NotNull @Min(0) @Max(3) Integer> answers) {
    }

    @PostMapping("/support/screens")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public SupportViews.ScreenOutcome screen(@Valid @RequestBody ScreenBody b) {
        return pathway.selfScreen(users.require(), Instrument.valueOf(b.instrument()), b.answers());
    }

    @GetMapping("/support/sharing")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public SupportViews.SharingView sharing() {
        return cases.mySharing(users.require());
    }

    public record SharingBody(@NotNull UUID caseId, @NotBlank @Pattern(regexp = Patterns.SHARING) String choice, @Size(max = 1000) String adjustments) {
    }

    @PutMapping("/support/sharing")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public SupportViews.SharingView setSharing(@Valid @RequestBody SharingBody b) {
        return cases.setSharing(users.require(), b.caseId(), Sharing.valueOf(b.choice()), b.adjustments());
    }

    // ------------------------------------------------------------------ peer support (pseudonymous)

    @GetMapping("/peer-support/peers")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<PeerSupportService.PeerView> peers() {
        return peers.peers(users.require());
    }

    public record ContactRequestBody(@NotBlank @Size(max = 60) @Pattern(regexp = "[\\p{L}0-9 .'\\-]{1,60}") String alias,
                                     @NotBlank @Pattern(regexp = Patterns.PHONE) String callbackPhone,
                                     @Pattern(regexp = "|" + Patterns.BASE) String base, @Pattern(regexp = "|" + Patterns.LANGUAGE) String language) {
    }

    @PostMapping("/peer-support/contact-request")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EMPLOYEE')")
    public PeerSupportService.ContactRequestResult contactRequest(@Valid @RequestBody ContactRequestBody b) {
        return peers.contactRequest(users.require(), b.alias(), b.callbackPhone(), b.base(), b.language());
    }

    // ------------------------------------------------------------------ CISM offers

    @GetMapping("/cism/offers")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<CismService.MyOffer> offers() {
        return cism.myOffers(users.require());
    }

    public record OfferResponseBody(@NotBlank @Pattern(regexp = Patterns.CISM_RESPONSE) String response,
                                    @Pattern(regexp = "|" + Patterns.CONTACT) String preferredContact) {
    }

    @PostMapping("/cism/offers/{id}/response")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public CismService.MyOffer respond(@PathVariable UUID id, @Valid @RequestBody OfferResponseBody b) {
        return cism.respond(users.require(), id, b.response(), b.preferredContact() == null || b.preferredContact().isEmpty() ? null : b.preferredContact());
    }
}
