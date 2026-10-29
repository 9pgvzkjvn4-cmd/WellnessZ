package com.wellnessz.e04.web;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

import com.wellnessz.e04.provider.ProviderDirectoryService;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E04-F03 provider network directory, contracts, network gaps and scorecards. */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {

    private final ProviderDirectoryService providers;
    private final TreatmentSupport support;
    private final CurrentUserProvider users;

    public ProviderController(ProviderDirectoryService providers, TreatmentSupport support, CurrentUserProvider users) {
        this.providers = providers;
        this.support = support;
        this.users = users;
    }

    @GetMapping
    @PreAuthorize(Spel.PROVIDER_READ)
    public List<ProviderDirectoryService.ProviderView> list(@RequestParam(required = false) @Pattern(regexp = Spel.CODE) String serviceCode,
                                                            @RequestParam(required = false) @Size(max = 60) String specialty,
                                                            @RequestParam(required = false) @Size(max = 100) String city,
                                                            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
                                                            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|EXPIRING|EXPIRED|NONE") String contractStatus,
                                                            @RequestParam(required = false) @Size(max = 100) String q,
                                                            @RequestParam(required = false) Boolean preferredOnly) {
        return providers.list(users.require(), serviceCode, specialty, city, countryCode, contractStatus, q, preferredOnly);
    }

    @GetMapping("/network-gaps")
    @PreAuthorize("hasAnyRole('PROCUREMENT','OH_ADMIN','CMO')")
    public List<ProviderDirectoryService.Gap> gaps() {
        return providers.gaps(users.require());
    }

    @GetMapping("/scorecards")
    @PreAuthorize(Spel.SCORECARDS)
    public List<ProviderDirectoryService.ScorecardView> scorecards(@RequestParam(required = false) @Pattern(regexp = Spel.MONTH) String month) {
        return providers.scorecards(users.require(), month(month));
    }

    @PostMapping("/scorecards/recompute")
    @PreAuthorize(Spel.SCORECARDS)
    public Map<String, Object> recompute(@RequestParam(required = false) @Pattern(regexp = Spel.MONTH) String month) {
        YearMonth m = month(month);
        return Map.of("month", m.toString(), "providers", providers.recompute(m));
    }

    @GetMapping(value = "/scorecards/export", produces = "text/csv")
    @PreAuthorize(Spel.SCORECARDS)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @Pattern(regexp = Spel.MONTH) String month) {
        YearMonth m = month(month);
        byte[] csv = providers.scorecardsCsv(users.require(), m).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("provider-scorecards-" + m + ".csv").build().toString())
                .body(csv);
    }

    private YearMonth month(String month) {
        return month == null ? YearMonth.from(support.today()).minusMonths(1) : YearMonth.parse(month);
    }

    @GetMapping("/{code}")
    @PreAuthorize(Spel.PROVIDER_READ)
    public ProviderDirectoryService.ProviderView get(@PathVariable @Pattern(regexp = Spel.ORG) String code) {
        return providers.get(users.require(), code);
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAnyRole('PROCUREMENT','OH_ADMIN')")
    public ProviderDirectoryService.ProviderView update(@PathVariable @Pattern(regexp = Spel.ORG) String code,
                                                        @Valid @RequestBody ProviderDirectoryService.ProfileCommand cmd) {
        return providers.updateProfile(users.require(), code, cmd);
    }

    @PostMapping("/{code}/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PROCUREMENT')")
    public ProviderDirectoryService.ProviderView contract(@PathVariable @Pattern(regexp = Spel.ORG) String code,
                                                          @Valid @RequestBody ProviderDirectoryService.ContractCommand cmd) {
        return providers.addContract(users.require(), code, cmd);
    }
}
