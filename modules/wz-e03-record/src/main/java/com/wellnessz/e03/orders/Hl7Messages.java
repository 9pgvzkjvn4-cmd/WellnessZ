package com.wellnessz.e03.orders;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.e03.support.CodeRef;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.DataTypeException;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.datatype.CE;
import ca.uhn.hl7v2.model.v25.group.ORM_O01_ORDER;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v25.message.ORM_O01;
import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import ca.uhn.hl7v2.model.v25.segment.MSH;
import ca.uhn.hl7v2.model.v25.segment.OBR;
import ca.uhn.hl7v2.model.v25.segment.OBX;
import ca.uhn.hl7v2.model.v25.segment.ORC;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.util.idgenerator.InMemoryIDGenerator;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/**
 * HL7 v2.5 messages for the partner laboratory (E03-BL-010): outbound ORM^O01 new orders (pulled by the lab gateway) and
 * inbound ORU^R01 results (OBX segments → results).
 */
@Component
public class Hl7Messages {

    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final HapiContext context;

    public Hl7Messages() {
        this.context = new DefaultHapiContext();
        this.context.setValidationContext(ValidationContextFactory.defaultValidation());
        this.context.getParserConfiguration().setIdGenerator(new InMemoryIDGenerator());
    }

    /** Builds an ORM^O01 (one ORC/OBR pair per ordered test) for the lab gateway pull queue. */
    public String orm(String placerOrderNumber, String employeeId, String displayName, String orderedBy, String priority,
                      List<CodeRef> items, Instant now) {
        try {
            ORM_O01 orm = new ORM_O01();
            orm.setParser(context.getPipeParser());
            MSH msh = orm.getMSH();
            msh.getFieldSeparator().setValue("|");
            msh.getEncodingCharacters().setValue("^~\\&");
            msh.getSendingApplication().getNamespaceID().setValue("WELLNESSZ");
            msh.getSendingFacility().getNamespaceID().setValue("OH");
            msh.getReceivingApplication().getNamespaceID().setValue("LIS");
            msh.getDateTimeOfMessage().getTime().setValue(TS.format(now));
            msh.getMessageType().getMessageCode().setValue("ORM");
            msh.getMessageType().getTriggerEvent().setValue("O01");
            msh.getMessageType().getMessageStructure().setValue("ORM_O01");
            msh.getMessageControlID().setValue(placerOrderNumber + "-" + UUID.randomUUID().toString().substring(0, 8));
            msh.getProcessingID().getProcessingID().setValue("P");
            msh.getVersionID().getVersionID().setValue("2.5");
            PID pid = orm.getPATIENT().getPID();
            pid.getSetIDPID().setValue("1");
            pid.getPatientIdentifierList(0).getIDNumber().setValue(employeeId);
            pid.getPatientIdentifierList(0).getIdentifierTypeCode().setValue("EI");
            if (displayName != null) {
                int space = displayName.lastIndexOf(' ');
                pid.getPatientName(0).getFamilyName().getSurname().setValue(space > 0 ? displayName.substring(space + 1) : displayName);
                if (space > 0) {
                    pid.getPatientName(0).getGivenName().setValue(displayName.substring(0, space));
                }
            }
            String ts = TS.format(now);
            for (int i = 0; i < items.size(); i++) {
                CodeRef c = items.get(i);
                ORM_O01_ORDER order = orm.getORDER(i);
                ORC orc = order.getORC();
                orc.getOrderControl().setValue("NW");
                orc.getPlacerOrderNumber().getEntityIdentifier().setValue(placerOrderNumber);
                orc.getDateTimeOfTransaction().getTime().setValue(ts);
                orc.getOrderingProvider(0).getIDNumber().setValue(orderedBy);
                OBR obr = order.getORDER_DETAIL().getOBR();
                obr.getSetIDOBR().setValue(String.valueOf(i + 1));
                obr.getPlacerOrderNumber().getEntityIdentifier().setValue(placerOrderNumber);
                obr.getUniversalServiceIdentifier().getIdentifier().setValue(c.code());
                obr.getUniversalServiceIdentifier().getText().setValue(c.display());
                obr.getUniversalServiceIdentifier().getNameOfCodingSystem().setValue(hl7System(c.system()));
                obr.getPriorityOBR().setValue("URGENT".equals(priority) ? "S" : "R");
                obr.getRequestedDateTime().getTime().setValue(ts);
            }
            return orm.encode();
        } catch (HL7Exception e) {
            throw new IllegalStateException("Unable to build ORM^O01", e);
        }
    }

    static String hl7System(String system) {
        return switch (system) {
            case "LOINC" -> "LN";
            case "SNOMED_CT" -> "SCT";
            case "ICD_10" -> "I10";
            default -> "L";
        };
    }

    static String system(String hl7) {
        if (hl7 == null) {
            return "LOCAL";
        }
        return switch (hl7) {
            case "LN" -> "LOINC";
            case "SCT", "SNM", "SNOMED" -> "SNOMED_CT";
            case "I10" -> "ICD_10";
            default -> "LOCAL";
        };
    }

    /** One OBX of an ORU^R01. */
    public record Observation(String employeeId, String placerOrderNumber, CodeRef code, String value, String unit, String referenceRange,
                              String interpretation, String status, Instant effectiveAt) {
    }

    /** Extracts the observations of an ORU^R01 v2.5 message (other versions are rejected). */
    public List<Observation> observations(Message message) throws HL7Exception {
        if (!(message instanceof ORU_R01 oru)) {
            throw new HL7Exception("Only ORU^R01 v2.5 results are supported");
        }
        List<Observation> out = new ArrayList<>();
        for (int p = 0; p < oru.getPATIENT_RESULTReps(); p++) {
            ORU_R01_PATIENT_RESULT pr = oru.getPATIENT_RESULT(p);
            String employeeId = pr.getPATIENT().getPID().getPatientIdentifierList(0).getIDNumber().getValue();
            for (int o = 0; o < pr.getORDER_OBSERVATIONReps(); o++) {
                ORU_R01_ORDER_OBSERVATION oo = pr.getORDER_OBSERVATION(o);
                String placer = oo.getOBR().getPlacerOrderNumber().getEntityIdentifier().getValue();
                if (placer == null || placer.isBlank()) {
                    placer = oo.getORC().getPlacerOrderNumber().getEntityIdentifier().getValue();
                }
                for (int x = 0; x < oo.getOBSERVATIONReps(); x++) {
                    OBX obx = oo.getOBSERVATION(x).getOBX();
                    CE id = obx.getObservationIdentifier();
                    String value = obx.getObservationValueReps() == 0 ? null : obx.getObservationValue(0).getData().encode();
                    String flag = obx.getAbnormalFlagsReps() == 0 ? null : obx.getAbnormalFlags(0).getValue();
                    out.add(new Observation(employeeId, placer,
                            new CodeRef(system(id.getNameOfCodingSystem().getValue()), id.getIdentifier().getValue(), id.getText().getValue()),
                            value, obx.getUnits().getIdentifier().getValue(), obx.getReferencesRange().getValue(), flag,
                            status(obx.getObservationResultStatus().getValue()), time(obx)));
                }
            }
        }
        return out;
    }

    static String status(String hl7) {
        if (hl7 == null) {
            return "FINAL";
        }
        return switch (hl7) {
            case "P", "I", "R", "S" -> "PRELIMINARY";
            case "C" -> "AMENDED";
            default -> "FINAL";
        };
    }

    private static Instant time(OBX obx) {
        try {
            Date d = obx.getDateTimeOfTheObservation().getTime().getValueAsDate();
            return d == null ? null : d.toInstant();
        } catch (DataTypeException e) {
            return null;
        }
    }
}
