package io.intimed.web;

import io.intimed.common.ApiException;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.partner.BadgeService;
import io.intimed.partner.PartnerSubmissionService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Partner programme API: vendor self-service submissions (IM-E02-BL-006) and certification badges (IM-E07-BL-005).
 * Vendors (PARTNER_DEV) submit and track their own submissions; Device Administrators / Regulatory reviewers move them
 * through review, harness and four-eyes certification.
 */
@RestController
@RequestMapping("/api/partner")
public class PartnerController {
    /** Upper bound of a submission request (5 MB specification as Base64 + SBOM + MDS2 + driver package). */
    static final long MAX_SUBMISSION_BYTES = 12L * 1024 * 1024;
    private final PartnerSubmissionService submissions;
    private final BadgeService badges;
    private final DriverRegistry drivers;

    public PartnerController(PartnerSubmissionService submissions, BadgeService badges, DriverRegistry drivers) {
        this.submissions = submissions;
        this.badges = badges;
        this.drivers = drivers;
    }

    private static String t() { return Current.tenant(); }
    private static String a() { return Current.actor(); }

    @PostMapping("/submissions")
    public ObjectNode submit(@RequestBody JsonNode b, HttpServletRequest req) {
        if (req.getContentLengthLong() > MAX_SUBMISSION_BYTES) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "too-large", "submission exceeds 12 MB");
        return submissions.submit(t(), b, a());
    }

    @GetMapping("/submissions") public List<ObjectNode> list() { return submissions.list(t()); }
    @GetMapping("/submissions/{id}") public ObjectNode get(@PathVariable String id) { return submissions.get(t(), id); }
    @GetMapping("/submissions/{id}/interface-spec") public ObjectNode spec(@PathVariable String id) { return submissions.interfaceSpec(t(), id); }
    @GetMapping("/submissions/{id}/audit") public List<Map<String, Object>> audit(@PathVariable String id) { return submissions.auditTrail(t(), id); }
    @PostMapping("/submissions/{id}/review") public ObjectNode review(@PathVariable String id) { return submissions.startReview(t(), id, a()); }
    @PostMapping("/submissions/{id}/harness") public ObjectNode harness(@PathVariable String id, @RequestBody JsonNode b) { return submissions.recordHarness(t(), id, b, a()); }
    @PostMapping("/submissions/{id}/certify") public ObjectNode certify(@PathVariable String id, @RequestBody(required = false) JsonNode b) {
        return submissions.certify(t(), id, b == null ? null : b.path("comment").asString(null), a());
    }
    @PostMapping("/submissions/{id}/reject") public ObjectNode reject(@PathVariable String id, @RequestBody JsonNode b) { return submissions.reject(t(), id, b.path("reason").asString(null), a()); }
    @PostMapping("/submissions/{id}/withdraw") public ObjectNode withdraw(@PathVariable String id) { return submissions.withdraw(t(), id, a()); }

    @GetMapping("/badges") public List<ObjectNode> badges(@RequestParam(required = false) String status) { return badges.list(t(), status); }
    @PostMapping("/badges/{id}/revoke") public ObjectNode revoke(@PathVariable String id, @RequestBody JsonNode b) {
        Current.require(Role.DEVICE_ADMIN, Role.REGULATORY);
        return badges.revoke(t(), id, b.path("reason").asString(null), a());
    }

    /** Withdraws a tenant-published driver (revokes its badge automatically). */
    @PostMapping("/drivers/{ref}/withdraw") public Map<String, Object> withdrawDriver(@PathVariable String ref, @RequestBody(required = false) JsonNode b) {
        Current.require(Role.DEVICE_ADMIN, Role.REGULATORY);
        drivers.withdraw(t(), ref, b == null ? "" : b.path("reason").asString(""), a());
        return Map.of("withdrawn", ref);
    }
}
