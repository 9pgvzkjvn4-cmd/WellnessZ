package com.wellnessz.e12.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e12.campaign.PromotionCampaignService;
import com.wellnessz.e12.campaign.PromotionAnalyticsService;
import com.wellnessz.e12.ergonomics.ErgonomicsService;
import com.wellnessz.e12.screening.ScreeningService;
import com.wellnessz.e12.wearable.WearableService;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Employee app (/me), manager workspace (/workspace) and internal integrations (campaign app, wearable aggregator). */
@RestController
@RequestMapping("/api/v1")
public class PromotionSelfServiceController {

    private final PromotionCampaignService campaigns;
    private final PromotionAnalyticsService analytics;
    private final ScreeningService screening;
    private final ErgonomicsService ergonomics;
    private final WearableService wearables;
    private final CurrentUserProvider users;

    public PromotionSelfServiceController(PromotionCampaignService campaigns, PromotionAnalyticsService analytics, ScreeningService screening,
                                          ErgonomicsService ergonomics, WearableService wearables, CurrentUserProvider users) {
        this.campaigns = campaigns;
        this.analytics = analytics;
        this.screening = screening;
        this.ergonomics = ergonomics;
        this.wearables = wearables;
        this.users = users;
    }

    // ------------------------------------------------------------------ employee app

    public record MyCampaigns(List<PromotionAnalyticsService.MyInvitation> invitations, List<ScreeningService.MyScreening> screenings,
                              List<WearableService.EnrolmentView> enrolments) {
    }

    @GetMapping("/me/campaigns")
    @PreAuthorize(Spel.EMPLOYEE)
    public MyCampaigns myCampaigns() {
        CurrentUser me = users.require();
        if (me.employeeId() == null) {
            throw ApiException.forbidden("Only employees have campaign invitations");
        }
        return new MyCampaigns(analytics.myInvitations(me.employeeId()), screening.mine(me.employeeId()), wearables.mine(me.employeeId()));
    }

    @PostMapping("/me/wearables/enrolments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.EMPLOYEE)
    public WearableService.EnrolmentView enrol(@Valid @RequestBody WearableService.EnrolCommand cmd) {
        return wearables.enrol(users.require(), cmd);
    }

    @DeleteMapping("/me/wearables/enrolments/{id}")
    @PreAuthorize(Spel.EMPLOYEE)
    public WearableService.EnrolmentView withdraw(@PathVariable UUID id) {
        return wearables.withdraw(users.require(), id);
    }

    // ------------------------------------------------------------------ manager workspace

    @GetMapping("/workspace/campaigns")
    @PreAuthorize(Spel.MANAGER)
    public List<PromotionAnalyticsService.UnitParticipation> workspaceCampaigns() {
        return analytics.workspace(users.require());
    }

    @GetMapping("/workspace/ergonomics")
    @PreAuthorize(Spel.MANAGER)
    public ErgonomicsService.Workspace workspaceErgonomics() {
        return ergonomics.workspace(users.require());
    }

    // ------------------------------------------------------------------ internal integrations

    @PostMapping("/integration/promotion/participation")
    @PreAuthorize(Spel.INTEGRATION)
    public PromotionCampaignService.IngestResult participation(@RequestBody @Valid @NotEmpty @Size(max = 5000) List<PromotionCampaignService.ParticipationRow> rows) {
        return campaigns.ingest(users.require(), rows);
    }

    @PostMapping("/integration/wearables/data")
    @PreAuthorize(Spel.INTEGRATION)
    public WearableService.IngestResult wearableData(@RequestBody @Valid @NotEmpty @Size(max = 10000) List<WearableService.DataRow> rows) {
        return wearables.ingest(users.require(), rows);
    }
}
