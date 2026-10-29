package com.wellnessz.security.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/consents")
public class ConsentController {

    private final ConsentService service;
    private final CurrentUserProvider users;

    public ConsentController(ConsentService service, CurrentUserProvider users) {
        this.service = service;
        this.users = users;
    }

    public record GrantRequest(@NotNull ConsentPurpose purpose, @Size(max = 500) String scope) {
    }

    public record ConsentView(UUID id, String employeeId, ConsentPurpose purpose, String scope, Consent.Status status,
                              String noticeVersion, Instant grantedAt, Instant withdrawnAt, String capturedBy) {
        static ConsentView of(Consent c) {
            return new ConsentView(c.getId(), c.getEmployeeId(), c.getPurpose(), c.getScope(), c.getStatus(),
                    c.getNoticeVersion(), c.getGrantedAt(), c.getWithdrawnAt(), c.getCapturedBy());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentView grant(@Valid @RequestBody GrantRequest request) {
        return ConsentView.of(service.grant(users.require(), request.purpose(), request.scope()));
    }

    @GetMapping("/me")
    public List<ConsentView> mine() {
        return service.mine(users.require()).stream().map(ConsentView::of).toList();
    }

    @DeleteMapping("/{id}")
    public ConsentView withdraw(@PathVariable UUID id) {
        return ConsentView.of(service.withdraw(users.require(), id));
    }
}
