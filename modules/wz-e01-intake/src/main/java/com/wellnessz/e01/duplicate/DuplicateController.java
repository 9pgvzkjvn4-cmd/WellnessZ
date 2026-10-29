package com.wellnessz.e01.duplicate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/cases/{id}")
public class DuplicateController {

    private final DuplicateService service;
    private final CurrentUserProvider users;

    public DuplicateController(DuplicateService service, CurrentUserProvider users) {
        this.service = service;
        this.users = users;
    }

    public record MergeRequest(@NotNull UUID intoCaseId, @NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping("/duplicates")
    public List<DuplicateService.Candidate> candidates(@PathVariable UUID id) {
        return service.candidates(users.require(), id);
    }

    @PostMapping("/merge")
    public Map<String, String> merge(@PathVariable UUID id, @Valid @RequestBody MergeRequest r) {
        service.merge(users.require(), id, r.intoCaseId(), r.reason());
        return Map.of("status", "MERGED", "intoCaseId", r.intoCaseId().toString());
    }
}
