package com.wellnessz.e09.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import com.wellnessz.e09.casework.EapService;
import com.wellnessz.e09.model.MentalHealthEnums.ClosureReason;
import com.wellnessz.e09.model.MentalHealthEnums.EapStatus;
import com.wellnessz.e09.support.MhSupport;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Partner portal for the EAP / counselling partner (contract §3.2): its own referrals only, minimal data, feedback limited to
 * status, attendance and closure reason. Any other field (e.g. free text) is refused with 422 {@code field_not_allowed}.
 */
@RestController("e09EapPartnerController")
@RequestMapping("/api/v1/partner/eap-referrals")
public class EapPartnerController {

    private final EapService eap;
    private final CurrentUserProvider users;

    public EapPartnerController(EapService eap, CurrentUserProvider users) {
        this.eap = eap;
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasRole('PARTNER_PROVIDER')")
    public List<EapService.PartnerReferralView> list(@RequestParam(required = false) @Pattern(regexp = Patterns.EAP_STATUS) String status) {
        return eap.partnerList(users.require(), status);
    }

    public record AttendanceBody(@NotNull LocalDate date, boolean attended) {
    }

    /** Feedback body; unknown properties are collected and refused – the partner can send no free text. */
    public static final class FeedbackBody {
        @NotBlank
        @Pattern(regexp = Patterns.EAP_STATUS)
        private String status;
        @Size(max = 24)
        private List<@Valid @NotNull AttendanceBody> attendance;
        @Pattern(regexp = Patterns.CLOSURE)
        private String closureReason;
        private final Map<String, Object> unknown = new LinkedHashMap<>();

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<AttendanceBody> getAttendance() { return attendance; }
        public void setAttendance(List<AttendanceBody> attendance) { this.attendance = attendance; }
        public String getClosureReason() { return closureReason; }
        public void setClosureReason(String closureReason) { this.closureReason = closureReason; }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            unknown.put(name, value);
        }

        boolean hasUnknown() {
            return !unknown.isEmpty();
        }
    }

    @PostMapping("/{id}/feedback")
    @PreAuthorize("hasRole('PARTNER_PROVIDER')")
    public EapService.PartnerReferralView feedback(@PathVariable UUID id, @Valid @RequestBody FeedbackBody b) {
        if (b.hasUnknown()) {
            throw MhSupport.unprocessable("field_not_allowed", "Feedback is limited to status, attendance and closure reason");
        }
        List<EapService.Attendance> att = b.getAttendance() == null ? List.of()
                : b.getAttendance().stream().map(a -> new EapService.Attendance(a.date(), a.attended())).toList();
        return eap.feedback(users.require(), id, new EapService.Feedback(EapStatus.valueOf(b.getStatus()), att,
                b.getClosureReason() == null || b.getClosureReason().isEmpty() ? null : ClosureReason.valueOf(b.getClosureReason())));
    }
}
