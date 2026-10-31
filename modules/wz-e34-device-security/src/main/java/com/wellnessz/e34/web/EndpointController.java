package com.wellnessz.e34.web;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.device.DeviceCodes.EndpointKind;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.events.StoredSecurityEvent;
import com.wellnessz.e34.identity.EndpointService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Endpoint identities (contract §15.1): inventory, creation with one-time enrolment token, rotation, isolation, release, revocation. */
@RestController
@RequestMapping("/api/v1/device-security/endpoints")
public class EndpointController {

    public record CreateRequest(@NotNull EndpointKind kind, @NotNull @Pattern(regexp = "[A-Z0-9]{2,10}") String siteCode,
                                @Pattern(regexp = "[A-Z_]{2,30}") String zone, @Pattern(regexp = "[A-Z0-9_]{2,30}") String tenantCode,
                                @Size(max = 50) List<@Pattern(regexp = Spel.ENDPOINT_ID) String> deviceInstanceIds,
                                @Size(max = 10) List<@Size(max = 64) String> allowedCidrs) {
    }

    public record StatusRequest(@Pattern(regexp = "[A-Z][A-Z0-9_]{2,59}") String reasonCode, @Size(max = 500) String note) {
    }

    private final EndpointService endpoints;
    private final SecurityEventService events;
    private final CurrentUserProvider users;

    public EndpointController(EndpointService endpoints, SecurityEventService events, CurrentUserProvider users) {
        this.endpoints = endpoints;
        this.events = events;
        this.users = users;
    }

    @GetMapping
    @PreAuthorize(Spel.SECOPS_OR_ADMIN)
    public List<EndpointService.EndpointView> list(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9]{2,10}") String siteCode,
                                                   @RequestParam(required = false) @Pattern(regexp = "PENDING_ENROLMENT|ACTIVE|ISOLATED|REVOKED") String status,
                                                   @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{3,30}") String kind,
                                                   @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        return endpoints.list(siteCode, status, kind, limit);
    }

    @GetMapping("/{endpointId}")
    @PreAuthorize(Spel.SECOPS_OR_ADMIN)
    public EndpointService.EndpointView get(@PathVariable @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId) {
        return endpoints.get(endpointId);
    }

    @PostMapping
    @PreAuthorize(Spel.SECOPS_OR_ADMIN)
    public ResponseEntity<EndpointService.Issued> create(@Valid @RequestBody CreateRequest r) {
        EndpointService.Issued issued = endpoints.create(users.require().username(), new EndpointService.CreateCommand(r.kind(), r.siteCode(),
                r.zone(), r.tenantCode(), r.deviceInstanceIds(), r.allowedCidrs()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(issued);
    }

    @PostMapping("/{endpointId}/enrolment-token")
    @PreAuthorize(Spel.SECOPS_OR_ADMIN)
    public ResponseEntity<EndpointService.Issued> reissueEnrolmentToken(@PathVariable @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(endpoints.reissueEnrolmentToken(users.require().username(), endpointId));
    }

    @PostMapping("/{endpointId}/rotate-token")
    @PreAuthorize(Spel.SECOPS)
    public ResponseEntity<EndpointService.Rotated> rotate(@PathVariable @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(endpoints.rotateToken(users.require().username(), endpointId));
    }

    @PostMapping("/{endpointId}/isolate")
    @PreAuthorize(Spel.SECOPS)
    public EndpointService.StatusChange isolate(@PathVariable @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId,
                                                @Valid @RequestBody(required = false) StatusRequest r) {
        StatusRequest req = r == null ? new StatusRequest(null, null) : r;
        return endpoints.isolate(users.require().username(), endpointId, req.reasonCode(), req.note());
    }

    @PostMapping("/{endpointId}/release")
    @PreAuthorize(Spel.SECOPS)
    public EndpointService.StatusChange release(@PathVariable @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId,
                                                @Valid @RequestBody(required = false) StatusRequest r) {
        StatusRequest req = r == null ? new StatusRequest(null, null) : r;
        return endpoints.release(users.require().username(), endpointId, req.reasonCode(), req.note());
    }

    @PostMapping("/{endpointId}/revoke")
    @PreAuthorize(Spel.SECOPS)
    public EndpointService.StatusChange revoke(@PathVariable @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId,
                                               @Valid @RequestBody(required = false) StatusRequest r) {
        StatusRequest req = r == null ? new StatusRequest(null, null) : r;
        return endpoints.revokeEndpoint(users.require().username(), endpointId, req.reasonCode(), req.note());
    }

    @GetMapping("/{endpointId}/events")
    @PreAuthorize(Spel.SECOPS)
    public List<StoredSecurityEvent> events(@PathVariable @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId,
                                            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        endpoints.get(endpointId);
        return events.query(null, endpointId, from, to, limit);
    }
}
