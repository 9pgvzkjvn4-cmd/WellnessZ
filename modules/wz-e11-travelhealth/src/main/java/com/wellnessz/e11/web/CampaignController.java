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

import com.wellnessz.e07.immunisation.ImmunisationService;
import com.wellnessz.e11.campaign.UptakeMaths;
import com.wellnessz.e11.campaign.VaccinationCampaignService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E11-F04 vaccination campaigns: definition, invitations, fast administration entry, declines, adverse events, uptake. */
@RestController("e11CampaignController")
@RequestMapping("/api/v1")
public class CampaignController {

    private final VaccinationCampaignService campaigns;
    private final CurrentUserProvider users;

    public CampaignController(VaccinationCampaignService campaigns, CurrentUserProvider users) {
        this.campaigns = campaigns;
        this.users = users;
    }

    @PostMapping("/vaccination-campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CAMPAIGNS)
    public VaccinationCampaignService.CampaignView create(@Valid @RequestBody VaccinationCampaignService.CampaignCommand cmd) {
        return campaigns.create(users.require(), cmd);
    }

    @GetMapping("/vaccination-campaigns")
    @PreAuthorize(Spel.CLINICIANS)
    public List<VaccinationCampaignService.CampaignView> list() {
        return campaigns.list();
    }

    @GetMapping("/vaccination-campaigns/{id}")
    @PreAuthorize(Spel.CLINICIANS)
    public VaccinationCampaignService.CampaignView get(@PathVariable UUID id) {
        return campaigns.get(id);
    }

    @PostMapping("/vaccination-campaigns/{id}/invite")
    @PreAuthorize(Spel.CAMPAIGN_INVITE)
    public VaccinationCampaignService.InviteResult invite(@PathVariable UUID id,
                                                          @Valid @RequestBody(required = false) VaccinationCampaignService.InviteCommand cmd) {
        return campaigns.invite(users.require(), id, cmd);
    }

    @PostMapping("/vaccination-campaigns/{id}/administrations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public ImmunisationService.AdministrationView administer(@PathVariable UUID id,
                                                             @Valid @RequestBody VaccinationCampaignService.AdministrationCommand cmd) {
        return campaigns.administer(users.require(), id, cmd);
    }

    @PostMapping("/vaccination-campaigns/{id}/declines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public VaccinationCampaignService.DeclineView decline(@PathVariable UUID id, @Valid @RequestBody VaccinationCampaignService.DeclineCommand cmd) {
        return campaigns.decline(users.require(), id, cmd);
    }

    @PostMapping("/vaccination-campaigns/{id}/adverse-events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public VaccinationCampaignService.AdverseEventView adverseEvent(@PathVariable UUID id,
                                                                    @Valid @RequestBody VaccinationCampaignService.AdverseEventCommand cmd) {
        return campaigns.adverseEvent(users.require(), id, cmd);
    }

    @GetMapping("/vaccination-campaigns/{id}/adverse-events")
    @PreAuthorize(Spel.CLINICIANS)
    public List<VaccinationCampaignService.AdverseEventView> adverseEvents(@PathVariable UUID id) {
        return campaigns.adverseEvents(users.require(), id);
    }

    @GetMapping("/vaccination-campaigns/{id}/uptake")
    @PreAuthorize(Spel.UPTAKE)
    public List<UptakeMaths.Row> uptake(@PathVariable UUID id,
                                        @RequestParam(defaultValue = "SUBSIDIARY") @Pattern(regexp = "SUBSIDIARY|DEPARTMENT") String groupBy) {
        return campaigns.uptake(id, groupBy);
    }

    @GetMapping("/me/vaccination-campaigns")
    @PreAuthorize(Spel.EMPLOYEE)
    public List<VaccinationCampaignService.MyCampaign> mine() {
        return campaigns.mine(TravelController.employee(users.require()));
    }

    @PostMapping("/me/vaccination-campaigns/{id}/response")
    @PreAuthorize(Spel.EMPLOYEE)
    public VaccinationCampaignService.MyCampaign respond(@PathVariable UUID id, @Valid @RequestBody VaccinationCampaignService.ResponseCommand cmd) {
        return campaigns.respond(TravelController.employee(users.require()), id, cmd);
    }
}
