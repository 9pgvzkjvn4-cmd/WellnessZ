package com.wellnessz.e06.episode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.e06.model.AbsenceEnums;
import com.wellnessz.e06.model.Certificate;
import com.wellnessz.e06.model.AbsenceCertificateRepository;
import com.wellnessz.e06.model.Episode;
import com.wellnessz.e06.rules.AbsenceFacts;
import com.wellnessz.e06.support.AbsenceSupport;

/** Renders absence episodes for OH (with the reason category) or for the status-only audiences (employee, manager, HR). */
@Component("absenceEpisodeViews")
public class EpisodeViews {

    private final AbsenceCertificateRepository certificates;
    private final AbsenceSupport support;

    public EpisodeViews(AbsenceCertificateRepository certificates, AbsenceSupport support) {
        this.certificates = certificates;
        this.support = support;
    }

    /** {@code reasonCategory} is present for OH roles only (null fields are omitted from the JSON). */
    public record EpisodeView(UUID id, String sourceSystem, LocalDate startDate, LocalDate endDate, long days, String absenceType,
                              String reasonCategory, boolean certified, String certificateValidity, UUID caseId, boolean open) {
    }

    public List<EpisodeView> views(Collection<Episode> episodes, boolean oh) {
        if (episodes.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Certificate>> byEpisode = new HashMap<>();
        for (String employeeId : episodes.stream().map(Episode::getEmployeeId).distinct().toList()) {
            for (Certificate c : certificates.findByEmployeeIdOrderByFromDateDesc(employeeId)) {
                if (c.getEpisodeId() != null) {
                    byEpisode.computeIfAbsent(c.getEpisodeId(), k -> new ArrayList<>()).add(c);
                }
            }
        }
        LocalDate today = support.today();
        return episodes.stream().map(e -> new EpisodeView(e.getId(), e.getSourceSystem(), e.getStartDate(), e.getEndDate(),
                AbsenceFacts.fact(e).days(today), e.getAbsenceType(), oh ? e.getReasonCategory() : null, e.isCertified(),
                validity(e, byEpisode.getOrDefault(e.getId(), List.of())), e.getCaseId(), e.getEndDate() == null)).toList();
    }

    /** VALID when any certificate covering the episode is valid, INVALID when all are invalid, PENDING otherwise; null when none expected. */
    public static String validity(Episode e, List<Certificate> certs) {
        if (certs.isEmpty()) {
            return e.isCertified() ? AbsenceEnums.CertificateValidity.PENDING.name() : null;
        }
        List<AbsenceEnums.CertificateValidity> v = certs.stream().map(c -> AbsenceEnums.validity(c.getStatus())).toList();
        if (v.contains(AbsenceEnums.CertificateValidity.VALID)) {
            return AbsenceEnums.CertificateValidity.VALID.name();
        }
        if (v.stream().allMatch(x -> x == AbsenceEnums.CertificateValidity.INVALID)) {
            return AbsenceEnums.CertificateValidity.INVALID.name();
        }
        return AbsenceEnums.CertificateValidity.PENDING.name();
    }

    public String validity(Episode e) {
        return validity(e, certificates.findByEpisodeId(e.getId()));
    }
}
