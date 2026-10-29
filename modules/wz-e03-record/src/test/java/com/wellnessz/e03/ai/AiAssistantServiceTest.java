package com.wellnessz.e03.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e03.encounter.ClinicalEncounterService;
import com.wellnessz.e03.encounter.EncounterTemplates;
import com.wellnessz.e03.model.ClinicalEncounter;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;
import com.wellnessz.security.identity.CurrentUser;

class AiAssistantServiceTest {

    final ClinicalEncounterService encounters = mock(ClinicalEncounterService.class);
    final EncounterTemplates templates = mock(EncounterTemplates.class);
    final ConsentService consents = mock(ConsentService.class);
    final AuditService audit = mock(AuditService.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC);
    final CurrentUser nurse = new CurrentUser("s", "nurse1", "N1", "Nina", null, Set.of("OH_CLINICIAN"));
    final UUID id = UUID.randomUUID();
    final UUID consentId = UUID.randomUUID();

    final AiDocumentationProvider fake = new AiDocumentationProvider() {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public Draft draft(DraftRequest request) {
            return new Draft(Map.of("subjective", "Draft: " + request.transcript().length() + " chars"), Map.of("onsetDate", "2026-10-28"));
        }
    };

    ClinicalEncounter encounter(UUID consent) {
        ClinicalEncounter e = new ClinicalEncounter();
        e.setId(id);
        e.setEmployeeId("E1");
        e.setType("ILLNESS");
        e.setTemplateVersion(1);
        e.setStatus("DRAFT");
        e.setAiConsentId(consent);
        when(encounters.load(any(), eq(id), eq(AccessPolicy.Action.UPDATE), anyString()))
                .thenReturn(new ClinicalEncounterService.Loaded(e, null));
        when(templates.get("ILLNESS", 1)).thenReturn(new EncounterTemplates.Definition("ILLNESS", 1, "Illness", false, false, true, false,
                List.of(), List.of(new EncounterTemplates.Section("soap", "SOAP",
                List.of(new EncounterTemplates.Field("subjective", "S", "TEXTAREA", true, null, null, null))))));
        return e;
    }

    @Test
    void draftNeedsConsentThenAConfiguredProvider() {
        encounter(null);
        AiAssistantService none = new AiAssistantService(encounters, templates, consents, List.of(fake), audit, clock, "none");
        assertThatThrownBy(() -> none.draft(nurse, id, new AiAssistantService.DraftRequest("text")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("consent_required"));

        encounter(consentId);
        when(consents.isActive(consentId, "E1", ConsentPurpose.AI_DOCUMENTATION)).thenReturn(true);
        assertThatThrownBy(() -> none.draft(nurse, id, new AiAssistantService.DraftRequest("text")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ai_not_configured"));
    }

    @Test
    void configuredProviderReturnsADraftThatIsNotSavedAndIsAudited() {
        encounter(consentId);
        when(consents.isActive(consentId, "E1", ConsentPurpose.AI_DOCUMENTATION)).thenReturn(true);
        AiAssistantService service = new AiAssistantService(encounters, templates, consents, List.of(fake), audit, clock, "fake");
        AiAssistantService.DraftView v = service.draft(nurse, id, new AiAssistantService.DraftRequest("Cough for three days"));
        assertThat(v.provider()).isEqualTo("fake");
        assertThat(v.generatedAt()).isEqualTo(clock.instant());
        assertThat(v.draft().get("soap")).isEqualTo(Map.of("subjective", "Draft: 20 chars"));
        verify(audit).record(eq("AI_DRAFT"), eq("ENCOUNTER"), eq(id.toString()), eq(AuditService.Outcome.SUCCESS), anyString());
        verify(encounters, org.mockito.Mockito.never()).update(any(), any(), any());
    }
}
