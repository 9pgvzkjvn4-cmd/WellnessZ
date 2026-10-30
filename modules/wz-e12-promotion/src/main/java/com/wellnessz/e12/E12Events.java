package com.wellnessz.e12;

import java.time.LocalDate;

import com.wellnessz.common.events.DomainEvent;

/** Events emitted by E12 (contract §6) – identifiers, titles and dates only, never person-level health data. */
public final class E12Events {

    /** Outbound destination of the group's health-campaign application (flat allow-list in application.yml). */
    public static final String CAMPAIGN_APP = "campaign-app";

    private E12Events() {
    }

    /** Campaign app feed: campaign published (target units comma-separated – the guard requires flat payloads). */
    public record CampaignPublished(String messageId, String campaignId, String title, String risk, LocalDate startDate, LocalDate endDate,
                                    String targetUnits) implements DomainEvent {
        @Override
        public String eventType() {
            return "promotion.campaign.published.v1";
        }

        @Override
        public String aggregateType() {
            return "CAMPAIGN";
        }

        @Override
        public String aggregateId() {
            return campaignId;
        }
    }

    /** In-process: an abnormal screening result opened a follow-up case. */
    public record ScreeningFollowUpOpened(String resultId, String employeeId, String caseId) implements DomainEvent {
        @Override
        public String eventType() {
            return "promotion.screening.follow-up.v1";
        }

        @Override
        public String aggregateType() {
            return "SCREENING_RESULT";
        }

        @Override
        public String aggregateId() {
            return resultId;
        }
    }
}
