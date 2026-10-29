package com.wellnessz.e03.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e03.terminology.TerminologyCatalog;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Terminology services (E03-F03): search, favourites (user + role), recent codes, reporting mappings, releases. */
@RestController
@RequestMapping("/api/v1/terminology")
public class TerminologyController {

    static final String SYSTEM = "SNOMED_CT|ICD_10|ICD_11|LOINC|ODH";
    static final String CODE = "[A-Za-z0-9.\\-]{1,50}";

    private final TerminologyCatalog catalog;
    private final CurrentUserProvider users;

    public TerminologyController(TerminologyCatalog catalog, CurrentUserProvider users) {
        this.catalog = catalog;
        this.users = users;
    }

    @GetMapping("/search")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<TerminologyCatalog.Concept> search(@RequestParam(required = false) @Pattern(regexp = SYSTEM) String system,
                                                   @RequestParam @NotBlank @Size(max = 100) String q,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return catalog.search(system, q, limit);
    }

    public record FavouriteRequest(@NotBlank @Pattern(regexp = SYSTEM) String system, @NotBlank @Pattern(regexp = CODE) String code,
                                   @Pattern(regexp = "[A-Z_]{1,40}") String role) {
    }

    @GetMapping("/favourites")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<TerminologyCatalog.Favourite> favourites() {
        return catalog.favourites(users.require());
    }

    @PostMapping("/favourites")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public TerminologyCatalog.Favourite addFavourite(@Valid @RequestBody FavouriteRequest r) {
        return catalog.addFavourite(users.require(), r.system(), r.code(), r.role());
    }

    /** Accepts {@code {system, code}} as a JSON body or as query parameters. */
    @DeleteMapping("/favourites")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public Map<String, Integer> removeFavourite(@RequestParam(required = false) @Pattern(regexp = SYSTEM) String system,
                                                @RequestParam(required = false) @Pattern(regexp = CODE) String code,
                                                @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{1,40}") String role,
                                                @Valid @RequestBody(required = false) FavouriteRequest body) {
        String s = body != null ? body.system() : system;
        String c = body != null ? body.code() : code;
        String ro = body != null ? body.role() : role;
        if (s == null || c == null) {
            throw ApiException.badRequest("invalid_request", "system and code are required");
        }
        return Map.of("removed", catalog.removeFavourite(users.require(), s, c, ro));
    }

    @GetMapping("/recent")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<TerminologyCatalog.Concept> recent() {
        return catalog.recent(users.require());
    }

    @GetMapping("/mappings")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public TerminologyCatalog.Mapping mapping(@RequestParam @Pattern(regexp = SYSTEM) String system,
                                              @RequestParam @Pattern(regexp = CODE) String code) {
        return catalog.mapping(system, code);
    }

    @PostMapping("/concepts/import")
    @PreAuthorize("hasRole('OH_ADMIN')")
    public TerminologyCatalog.ImportResult importConcepts(@RequestBody @Size(min = 1, max = 20000) List<TerminologyCatalog.ImportItem> items,
                                                          @RequestParam(required = false) @Size(max = 60) @Pattern(regexp = "[A-Za-z0-9._\\-]{1,60}") String version,
                                                          @RequestParam(required = false) @Size(max = 300) String licence) {
        return catalog.importConcepts(users.require(), items, version, licence);
    }

    @GetMapping("/releases")
    @PreAuthorize("hasRole('OH_ADMIN')")
    public List<TerminologyCatalog.Release> releases() {
        return catalog.releases();
    }
}
