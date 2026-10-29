package com.wellnessz.e08.report;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.e08.E08Events;
import com.wellnessz.e08.support.ClaimsSupport;
import com.wellnessz.integration.outbox.EventBus;

/**
 * Default submission channel: an allow-listed, reference-only {@code claim.report.submitted.v1} event to the insurer or
 * authority gateway through the transactional outbox, plus a content-free e-mail to the recipient's contact address
 * (insurers are pointed to the partner portal, where the consented share is available). Runs inside the caller's transaction.
 */
@Component
public class DefaultClaimsSubmissionGateway implements ClaimsSubmissionGateway {

    private final EventBus bus;
    private final ClaimsSupport support;

    public DefaultClaimsSubmissionGateway(EventBus bus, ClaimsSupport support) {
        this.bus = bus;
        this.support = support;
    }

    @Override
    public Receipt submit(Submission s) {
        String messageId = UUID.randomUUID().toString();
        String incident = s.incidentDate() == null ? null : s.incidentDate().toString();
        if ("INSURER".equals(s.recipientType())) {
            bus.publish(new E08Events.InsurerReportSubmitted(messageId, s.claimRef(), s.reportRef(), s.formCode(), s.recipientCode(), s.employeeId(),
                    incident, s.submittedAt().toString(), s.deadlineAt().toString()), E08Events.INSURER_GATEWAY);
        } else {
            bus.publish(new E08Events.AuthorityReportSubmitted(messageId, s.reportRef(), s.formCode(), s.jurisdiction(),
                    support.config("claims.employer-ref", "WZ-EMP-0001"), incident, s.submittedAt().toString(), s.deadlineAt().toString()),
                    E08Events.AUTHORITY_GATEWAY);
        }
        if (!"API".equals(s.channel()) && s.documentNumber() != null) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("firstName", "colleagues");
            params.put("documentNumber", s.documentNumber());
            support.notifyOrganisation(s.recipientCode(), "document.shared", params,
                    "INSURER".equals(s.recipientType()) ? support.partnerPortal() + "/#/claims" : "reference " + s.reportRef());
        }
        return new Receipt(messageId);
    }
}
