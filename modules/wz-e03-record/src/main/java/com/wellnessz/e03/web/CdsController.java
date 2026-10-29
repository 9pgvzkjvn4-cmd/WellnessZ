package com.wellnessz.e03.web;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e03.ai.CaseSummaryService;
import com.wellnessz.e03.cds.CdsService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** CDS alert statistics (alert-fatigue monitoring, E03-F05) and the rule-based case handover summary (E03-F07). */
@RestController
@RequestMapping("/api/v1")
public class CdsController {

    private final CdsService cds;
    private final CaseSummaryService summaries;
    private final CurrentUserProvider users;

    public CdsController(CdsService cds, CaseSummaryService summaries, CurrentUserProvider users) {
        this.cds = cds;
        this.summaries = summaries;
        this.users = users;
    }

    /** {@code from} / {@code to} are dates (inclusive); default: the last 30 days. */
    @GetMapping("/cds/statistics")
    @PreAuthorize("hasAnyRole('CMO','NURSING_HEAD')")
    public CdsService.Statistics statistics(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end) || start.isBefore(end.minusYears(5))) {
            throw ApiException.badRequest("invalid_range", "from must be before to and within 5 years");
        }
        return cds.statistics(start.atStartOfDay(ZoneOffset.UTC).toInstant(), end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @GetMapping("/cases/{id}/summary")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public CaseSummaryService.CaseSummary caseSummary(@PathVariable UUID id) {
        return summaries.summary(users.require(), id);
    }
}
