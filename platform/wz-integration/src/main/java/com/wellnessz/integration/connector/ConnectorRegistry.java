package com.wellnessz.integration.connector;

import java.util.List;

import org.springframework.stereotype.Component;

/** Catalogue of the enterprise and external connectors in scope. Implementations are added per increment. */
@Component
public class ConnectorRegistry {

    private static final List<ConnectorDescriptor> CONNECTORS = List.of(
            new ConnectorDescriptor("hris", "HRIS / HCM", ConnectorDescriptor.Direction.IN, "REST/SCIM or SFTP batch",
                    List.of("employees", "org hierarchy", "job codes", "lifecycle events"), "master data (in)"),
            new ConnectorDescriptor("time-attendance", "Time & attendance / crew sickness", ConnectorDescriptor.Direction.IN,
                    "REST / event", List.of("absence episodes"), "absence episodes (in)"),
            new ConnectorDescriptor("crew-rostering", "Crew rostering / crew management", ConnectorDescriptor.Direction.OUT,
                    "event", List.of("fitnessStatus", "restrictionUntil", "effectiveFrom"), "fitness status + dates only (out)"),
            new ConnectorDescriptor("hr-systems", "HR systems / catering hygiene", ConnectorDescriptor.Direction.OUT, "event",
                    List.of("fitnessStatus", "restrictionUntil"), "fitness & restriction status only (out)"),
            new ConnectorDescriptor("labs", "Laboratories & clinic devices", ConnectorDescriptor.Direction.BOTH,
                    "HL7 v2 / FHIR R4", List.of("orders", "results"), "orders / results (both)"),
            new ConnectorDescriptor("partner-providers", "Partner hospitals, specialists, physio, EAP",
                    ConnectorDescriptor.Direction.BOTH, "FHIR R4 / portal", List.of("referrals", "reports"),
                    "referrals / reports (both, consent required)"),
            new ConnectorDescriptor("insurers", "Insurers / TPAs", ConnectorDescriptor.Direction.BOTH, "REST / FHIR Claim",
                    List.of("notifications", "pre-auth", "documents"), "consent required (both)"),
            new ConnectorDescriptor("erp-finance", "ERP / Finance", ConnectorDescriptor.Direction.OUT, "event / file",
                    List.of("caseCostCentre", "amount", "currency"), "case costs (out)"),
            new ConnectorDescriptor("sms-ehs", "Safety Management System / EHS dashboards", ConnectorDescriptor.Direction.OUT,
                    "event", List.of("anonymised metrics"), "anonymised OH metrics (out)"),
            new ConnectorDescriptor("ehs-incidents", "EHS incident-management system", ConnectorDescriptor.Direction.IN,
                    "Mailbox (IMAP / Microsoft Graph) or Excel import", List.of("incidents"), "primary incident feed (in)"),
            new ConnectorDescriptor("health-campaign", "Health-campaign app & consented wearables",
                    ConnectorDescriptor.Direction.BOTH, "REST", List.of("campaigns", "participation"), "consent required (both)"));

    public List<ConnectorDescriptor> all() {
        return CONNECTORS;
    }
}
