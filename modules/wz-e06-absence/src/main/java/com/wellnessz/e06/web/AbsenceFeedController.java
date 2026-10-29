package com.wellnessz.e06.web;

import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e06.feed.AbsenceFeedConnector;
import com.wellnessz.e06.feed.AbsenceFeedService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E06-F01 absence feed: push ingestion (machine clients), on-demand pull, data-quality runs and the mismatch list. */
@RestController
@RequestMapping("/api/v1")
public class AbsenceFeedController {

    private final AbsenceFeedService feed;
    private final CurrentUserProvider users;

    public AbsenceFeedController(AbsenceFeedService feed, CurrentUserProvider users) {
        this.feed = feed;
        this.users = users;
    }

    public record EpisodeRow(@NotBlank @Pattern(regexp = Patterns.REF) String sourceRef, @NotBlank @Pattern(regexp = Patterns.EMP) String employeeRef,
                             @NotNull LocalDate startDate, LocalDate endDate, @NotBlank @Pattern(regexp = Patterns.ABSENCE_TYPE) String absenceType,
                             @Pattern(regexp = Patterns.REASON) String reasonCategory, boolean certified) {
    }

    public record EpisodeBatch(@NotBlank @Pattern(regexp = Patterns.SOURCE) String sourceSystem,
                               @NotEmpty @Size(max = 5000) List<@Valid @NotNull EpisodeRow> episodes) {
    }

    @PostMapping("/integration/absence/episodes")
    @PreAuthorize("hasRole('INTEGRATION')")
    public AbsenceFeedService.IngestResult ingest(@Valid @RequestBody EpisodeBatch batch) {
        return feed.ingest(users.require().username(), batch.sourceSystem(), "PUSH", batch.episodes().stream()
                .map(r -> new AbsenceFeedConnector.FeedRow(r.sourceRef(), r.employeeRef(), r.startDate(), r.endDate(), r.absenceType(),
                        r.reasonCategory(), r.certified())).toList());
    }

    @PostMapping("/absence/feed/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OH_ADMIN')")
    public AbsenceFeedService.FeedRunView runNow() {
        return feed.pull(users.require().username());
    }

    @GetMapping("/absence/feed/runs")
    @PreAuthorize("hasAnyRole('OH_ADMIN','HR','NURSING_HEAD')")
    public List<AbsenceFeedService.FeedRunView> runs(@RequestParam(defaultValue = "30") @Min(1) @Max(200) int limit) {
        return feed.runs(limit);
    }

    @GetMapping("/absence/feed/mismatches")
    @PreAuthorize("hasAnyRole('HR','OH_ADMIN')")
    public List<AbsenceFeedService.MismatchView> mismatches(@RequestParam(required = false) @Pattern(regexp = "OPEN|RESOLVED|IGNORED") String status) {
        return feed.mismatches(users.require(), status);
    }

    public record ResolveRequest(@Pattern(regexp = Patterns.EMP) String employeeId, Boolean ignore, @Size(max = 500) String reason) {
    }

    @PostMapping("/absence/feed/mismatches/{id}/resolve")
    @PreAuthorize("hasAnyRole('HR','OH_ADMIN')")
    public AbsenceFeedService.MismatchView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest r) {
        return feed.resolve(users.require(), id, r.employeeId(), Boolean.TRUE.equals(r.ignore()), r.reason());
    }
}
