package com.wellnessz.e09.casework;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.casekernel.support.SupportProgrammeReferral;
import com.wellnessz.e09.casework.SupportCaseService.OpenSupport;
import com.wellnessz.e09.model.E09SupportCaseRepository;
import com.wellnessz.e09.model.MentalHealthEnums.Concern;
import com.wellnessz.e09.model.MentalHealthEnums.Risk;
import com.wellnessz.e09.model.MentalHealthEnums.Source;
import com.wellnessz.e09.model.SupportCase;
import com.wellnessz.e09.support.MhSupport;

/**
 * {@link SupportProgrammeReferral} for other modules (E10 substance programme, E11 exposures): opens a restricted support case
 * with source PROGRAMME_REFERRAL – idempotent per source object – and alerts the named clinicians content-free
 * ({@code support.urgent-callback}, level "programme referral"). The reason is a code, never clinical detail.
 */
@Component
public class SupportProgrammeReferralAdapter implements SupportProgrammeReferral {

    private final SupportCaseService cases;
    private final E09SupportCaseRepository repository;
    private final MhSupport support;
    private final ModuleRegistry modules;

    public SupportProgrammeReferralAdapter(SupportCaseService cases, E09SupportCaseRepository repository, MhSupport support, ModuleRegistry modules) {
        this.cases = cases;
        this.repository = repository;
        this.support = support;
        this.modules = modules;
    }

    @Override
    @Transactional
    public Optional<UUID> refer(String actor, String employeeId, UUID relatedCaseId, String reason, String sourceType, String sourceRef) {
        if (!modules.isEnabled("E09") || employeeId == null || support.employee(employeeId).isEmpty()) {
            return Optional.empty();
        }
        String type = sourceType == null || sourceType.isBlank() ? "PROGRAMME" : sourceType.strip();
        String ref = sourceRef == null || sourceRef.isBlank() ? (relatedCaseId == null ? null : relatedCaseId.toString()) : sourceRef.strip();
        if (ref != null) {
            Optional<SupportCase> existing = repository.findBySourceTypeAndSourceRef(type, ref);
            if (existing.isPresent()) {
                return Optional.of(existing.get().getId());
            }
        }
        String code = reason == null ? "OTHER" : reason.strip().toUpperCase(java.util.Locale.ROOT);
        Concern concern = switch (code) {
            case "SUBSTANCE_POSITIVE", "SUBSTANCE_REFUSAL" -> Concern.SUBSTANCE;
            case "CRITICAL_INCIDENT", "EXPOSURE" -> Concern.TRAUMA;
            default -> Concern.OTHER;
        };
        SupportCase c = cases.open(new OpenSupport(employeeId, Source.PROGRAMME_REFERRAL, concern, Risk.MODERATE,
                actor == null ? "system" : actor, type, ref));
        cases.event(c, "PROGRAMME_REFERRAL", "Programme referral (" + code + ") from " + type, actor);
        support.notifyClinicians("support.urgent-callback", Map.of("reference", c.getCaseRef(), "level", "programme referral"), "#/support/cases");
        return Optional.of(c.getId());
    }
}
