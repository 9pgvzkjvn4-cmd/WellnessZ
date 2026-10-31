package com.wellnessz.e33.web;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e33.service.KioskService;

/**
 * {@code /device-ingest/kiosk/**} (E33-owned machine path, contract 14.6): reachable only through the kernel device-ingest
 * filter chain (mTLS thumbprint + endpoint token, {@code ROLE_DEVICE_ENDPOINT}); the service additionally requires endpoint
 * kind {@code KIOSK} and that a kiosk session belongs to the calling kiosk.
 */
@RestController
@RequestMapping("/api/v1/device-ingest/kiosk")
public class KioskController {

    private final KioskService kiosk;

    public KioskController(KioskService kiosk) {
        this.kiosk = kiosk;
    }

    public record IdentifyRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String badgeId) {
    }

    public record ConsentRequest(@NotNull Boolean accepted, @Size(max = 20) String noticeVersion) {
    }

    @PostMapping("/identify")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.DEVICE_ENDPOINT)
    public KioskService.IdentifyResult identify(@Valid @RequestBody IdentifyRequest r) {
        return kiosk.identify(r.badgeId());
    }

    @PostMapping("/{kioskSessionId}/consent")
    @PreAuthorize(Spel.DEVICE_ENDPOINT)
    public KioskService.ConsentResult consent(@PathVariable UUID kioskSessionId, @Valid @RequestBody ConsentRequest r) {
        return kiosk.consent(kioskSessionId, r.accepted(), r.noticeVersion());
    }

    @GetMapping("/{kioskSessionId}/summary")
    @PreAuthorize(Spel.DEVICE_ENDPOINT)
    public KioskService.KioskSummary summary(@PathVariable UUID kioskSessionId) {
        return kiosk.summary(kioskSessionId);
    }
}
