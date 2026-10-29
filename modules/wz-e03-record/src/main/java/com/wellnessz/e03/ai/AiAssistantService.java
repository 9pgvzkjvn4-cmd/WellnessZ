package com.wellnessz.e03.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e03.encounter.ClinicalEncounterService;
import com.wellnessz.e03.encounter.EncounterTemplates;
import com.wellnessz.e03.model.ClinicalEncounter;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Consent-gated AI documentation assistant (E03-F07 / E03-BL-019): records the employee's consent (captured by the clinician),
 * produces a draft from a transcript through the configured {@link AiDocumentationProvider} and audits every use. Drafts are
 * returned only; the encounter is flagged {@code aiAssisted} when the clinician saves AI content.
 */
@Service
public class AiAssistantService {

    private final ClinicalEncounterService encounters;
    private final EncounterTemplates templates;
    private final ConsentService consents;
    private final List<AiDocumentationProvider> providers;
    private final AuditService audit;
    private final Clock clock;
    private final String providerName;

    public AiAssistantService(ClinicalEncounterService encounters, EncounterTemplates templates, ConsentService consents,
                              List<AiDocumentationProvider> providers, AuditService audit, Clock clock,
                              @Value("${wellnessz.record.ai.provider:none}") String providerName) {
        this.encounters = encounters;
        this.templates = templates;
        this.consents = consents;
        this.providers = List.copyOf(providers);
        this.audit = audit;
        this.clock = clock;
        this.providerName = providerName;
    }

    public record ConsentRequest(@NotNull @AssertTrue Boolean verbal, @NotBlank @Size(max = 1000) String statement) {
    }

    public record ConsentView(UUID consentId, String employeeId, String purpose, String capturedBy, Instant grantedAt) {
    }

    public record DraftRequest(@NotBlank @Size(max = 50000) String transcript) {
    }

    public record DraftView(Map<String, Object> draft, String provider, Instant generatedAt) {
    }

    @Transactional
    public ConsentView consent(CurrentUser user, UUID encounterId, ConsentRequest r) {
        ClinicalEncounterService.Loaded l = encounters.load(user, encounterId, AccessPolicy.Action.UPDATE, "AI_CONSENT");
        ClinicalEncounter e = l.encounter();
        Consent c = consents.grantOnBehalf(e.getEmployeeId(), ConsentPurpose.AI_DOCUMENTATION, "encounter:" + encounterId, user.username());
        e.setAiConsentId(c.getId());
        audit.record("AI_CONSENT_CAPTURED", "ENCOUNTER", encounterId.toString(), AuditService.Outcome.SUCCESS, "consent=" + c.getId());
        return new ConsentView(c.getId(), c.getEmployeeId(), c.getPurpose().name(), c.getCapturedBy(), c.getGrantedAt());
    }

    @Transactional(readOnly = true)
    public DraftView draft(CurrentUser user, UUID encounterId, DraftRequest r) {
        ClinicalEncounterService.Loaded l = encounters.load(user, encounterId, AccessPolicy.Action.UPDATE, "AI_DRAFT");
        ClinicalEncounter e = l.encounter();
        if (!"DRAFT".equals(e.getStatus())) {
            throw ApiException.conflict("not_draft", "AI drafts are only available for draft encounters");
        }
        if (e.getAiConsentId() == null || !consents.isActive(e.getAiConsentId(), e.getEmployeeId(), ConsentPurpose.AI_DOCUMENTATION)) {
            audit.record("AI_DRAFT", "ENCOUNTER", encounterId.toString(), AuditService.Outcome.DENIED, "No active AI documentation consent");
            throw ApiException.conflict("consent_required", "Record the employee's consent to AI-assisted documentation first");
        }
        AiDocumentationProvider provider = providers.stream().filter(p -> p.name().equals(providerName)).findFirst()
                .orElseThrow(() -> {
                    audit.record("AI_DRAFT", "ENCOUNTER", encounterId.toString(), AuditService.Outcome.FAILURE, "No AI provider configured");
                    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_not_configured",
                            "No AI documentation provider is configured");
                });
        EncounterTemplates.Definition t = templates.get(e.getType(), e.getTemplateVersion());
        List<String> keys = t.sections().stream().flatMap(s -> s.fields().stream().map(f -> ("soap".equals(s.key()) ? "soap." : "") + f.key()))
                .toList();
        AiDocumentationProvider.Draft draft = provider.draft(new AiDocumentationProvider.DraftRequest(e.getType(), e.getTemplateVersion(), keys,
                r.transcript()));
        Instant now = clock.instant();
        audit.record("AI_DRAFT", "ENCOUNTER", encounterId.toString(), AuditService.Outcome.SUCCESS,
                "provider=" + provider.name() + " transcriptChars=" + r.transcript().length());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("soap", draft.soap() == null ? Map.of() : draft.soap());
        body.put("answers", draft.answers() == null ? Map.of() : draft.answers());
        return new DraftView(body, provider.name(), now);
    }
}
