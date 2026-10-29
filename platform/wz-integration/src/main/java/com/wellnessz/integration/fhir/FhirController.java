package com.wellnessz.integration.fhir;

import java.util.Set;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.integration.inbound.InboundMessage;
import com.wellnessz.integration.inbound.InboundService;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;

/**
 * HL7 FHIR R4 inbound endpoint for laboratories, clinic devices and partner providers.
 * Only JSON is accepted (no XML parser → no XXE surface). Resource types are allow-listed.
 */
@RestController
@RequestMapping("/api/v1/integration/fhir")
public class FhirController {

    public static final String FHIR_JSON = "application/fhir+json";
    static final Set<String> SUPPORTED = Set.of("Patient", "Observation", "DiagnosticReport", "ServiceRequest",
            "Condition", "Encounter", "Immunization", "DocumentReference", "Bundle", "Appointment", "Claim");

    private final FhirContext ctx;
    private final InboundService inbound;

    public FhirController(FhirContext ctx, InboundService inbound) {
        this.ctx = ctx;
        this.inbound = inbound;
    }

    @GetMapping(value = "/metadata", produces = {FHIR_JSON, "application/json"})
    public String capabilityStatement() {
        CapabilityStatement cs = new CapabilityStatement();
        cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        cs.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.addFormat("json");
        CapabilityStatement.CapabilityStatementRestComponent rest = cs.addRest();
        rest.setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);
        SUPPORTED.stream().sorted().forEach(t -> rest.addResource().setType(t)
                .addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.CREATE));
        return FhirConfig.jsonParser(ctx).encodeResourceToString(cs);
    }

    @PostMapping(value = "/{resourceType}", consumes = {FHIR_JSON, "application/json"}, produces = {FHIR_JSON, "application/json"})
    public ResponseEntity<String> create(@PathVariable String resourceType, @RequestBody String body) {
        if (!SUPPORTED.contains(resourceType)) {
            return outcome(HttpStatus.BAD_REQUEST, OperationOutcome.IssueType.NOTSUPPORTED, "Resource type not supported");
        }
        IBaseResource resource;
        try {
            resource = FhirConfig.jsonParser(ctx).parseResource(body);
        } catch (DataFormatException e) {
            return outcome(HttpStatus.BAD_REQUEST, OperationOutcome.IssueType.STRUCTURE, "Invalid FHIR R4 JSON");
        }
        String actualType = ctx.getResourceType(resource);
        if (!resourceType.equals(actualType)) {
            return outcome(HttpStatus.BAD_REQUEST, OperationOutcome.IssueType.INVALID, "resourceType does not match the URL");
        }
        String externalId = resource instanceof Resource r && r.hasIdElement() ? r.getIdElement().getIdPart() : null;
        InboundMessage msg = inbound.accept("fhir", "FHIR_R4", resourceType, externalId, body);
        OperationOutcome oo = new OperationOutcome();
        oo.addIssue().setSeverity(OperationOutcome.IssueSeverity.INFORMATION).setCode(OperationOutcome.IssueType.INFORMATIONAL)
                .setDiagnostics("Accepted as " + msg.getId());
        return ResponseEntity.status(HttpStatus.CREATED).header("Location", "urn:uuid:" + msg.getId())
                .body(FhirConfig.jsonParser(ctx).encodeResourceToString(oo));
    }

    private ResponseEntity<String> outcome(HttpStatus status, OperationOutcome.IssueType type, String message) {
        OperationOutcome oo = new OperationOutcome();
        oo.addIssue().setSeverity(OperationOutcome.IssueSeverity.ERROR).setCode(type).setDiagnostics(message);
        return ResponseEntity.status(status).body(FhirConfig.jsonParser(ctx).encodeResourceToString(oo));
    }
}
