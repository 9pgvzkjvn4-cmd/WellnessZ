package com.wellnessz.e12.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
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

import com.wellnessz.e12.campaign.PromotionCampaignService;
import com.wellnessz.e12.campaign.PromotionAnalyticsService;
import com.wellnessz.e12.rules.InsightRules;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E12-F01 campaign planner + insights, E12-F04 publication, E12-F05 participation & outcome report. */
@RestController
@RequestMapping("/api/v1")
public class PromotionController {

    private static final String GROUP_BY = "(?i)unit|subsidiary|job_?code|location";

    private final PromotionCampaignService campaigns;
    private final PromotionAnalyticsService analytics;
    private final CurrentUserProvider users;

    public PromotionController(PromotionCampaignService campaigns, PromotionAnalyticsService analytics, CurrentUserProvider users) {
        this.campaigns = campaigns;
        this.analytics = analytics;
        this.users = users;
    }

    @GetMapping("/promotion/insights")
    @PreAuthorize(Spel.INSIGHTS)
    public List<InsightRules.Insight> insights(@RequestParam(required = false) @Pattern(regexp = PromotionCampaignService.RISK) String risk,
                                               @RequestParam(defaultValue = "12") @Min(1) @Max(36) int months) {
        return analytics.insights(users.require(), risk, months);
    }

    @GetMapping("/promotion/campaigns")
    @PreAuthorize(Spel.REPORT)
    public List<PromotionCampaignService.CampaignView> list(@RequestParam(required = false) @Pattern(regexp = PromotionCampaignService.STATUS) String status,
                                                   @RequestParam(required = false) @Pattern(regexp = PromotionCampaignService.RISK) String risk) {
        return campaigns.list(status, risk);
    }

    @GetMapping("/promotion/campaigns/{id}")
    @PreAuthorize(Spel.REPORT)
    public PromotionCampaignService.CampaignView get(@PathVariable UUID id) {
        return campaigns.get(id);
    }

    @PostMapping("/promotion/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.LEAD)
    public PromotionCampaignService.CampaignView create(@Valid @RequestBody PromotionCampaignService.CampaignCommand cmd) {
        return campaigns.create(users.require(), cmd);
    }

    @PutMapping("/promotion/campaigns/{id}")
    @PreAuthorize(Spel.LEAD)
    public PromotionCampaignService.CampaignView update(@PathVariable UUID id, @Valid @RequestBody PromotionCampaignService.CampaignCommand cmd) {
        return campaigns.update(users.require(), id, cmd);
    }

    @PostMapping("/promotion/campaigns/{id}/publish")
    @PreAuthorize(Spel.LEAD)
    public PromotionCampaignService.PublishResult publish(@PathVariable UUID id) {
        return campaigns.publish(users.require(), id);
    }

    @GetMapping(value = "/promotion/campaigns/{id}/report", params = "!format")
    @PreAuthorize(Spel.REPORT)
    public PromotionAnalyticsService.Report report(@PathVariable UUID id,
                                                   @RequestParam(required = false) @Pattern(regexp = GROUP_BY) String groupBy) {
        return analytics.report(users.require(), id, groupBy);
    }

    @GetMapping(value = "/promotion/campaigns/{id}/report", params = "format")
    @PreAuthorize(Spel.REPORT)
    public ResponseEntity<byte[]> reportCsv(@PathVariable UUID id, @RequestParam @Pattern(regexp = "csv") String format,
                                            @RequestParam(required = false) @Pattern(regexp = GROUP_BY) String groupBy) {
        byte[] csv = analytics.reportCsv(users.require(), id, groupBy).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("campaign-report.csv").build().toString())
                .body(csv);
    }
}
