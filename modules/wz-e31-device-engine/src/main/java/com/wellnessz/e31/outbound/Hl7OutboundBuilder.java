package com.wellnessz.e31.outbound;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.DataTypeException;
import ca.uhn.hl7v2.model.v25.message.ADT_A01;
import ca.uhn.hl7v2.model.v25.message.OML_O21;
import ca.uhn.hl7v2.model.v25.message.ORM_O01;
import ca.uhn.hl7v2.model.v25.message.QRY_R02;
import ca.uhn.hl7v2.model.v25.segment.MSH;
import ca.uhn.hl7v2.model.v25.segment.OBR;
import ca.uhn.hl7v2.model.v25.segment.ORC;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.model.v25.segment.PV1;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.util.idgenerator.InMemoryIDGenerator;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/**
 * Outbound HL7 v2.5 messages to devices that accept orders (E31-HLR-08, E31-BL-004, E31-BL-016; contract §7 worklist and §16.1),
 * built with HAPI: {@code ORM^O01} and {@code OML^O21} (new order, ORC-1 {@code NW}), {@code ADT^A04} (register) and
 * {@code ADT^A08} (update) for demographics push, and {@code QRY^R02} (original-mode query, QRD / QRF with the time range) for
 * historic retrieval from a device or data manager. Content is minimised: PID carries the employee id and name; date of birth
 * and sex only when the driver declares that it needs them ({@code protocol.worklistFields}).
 */
public final class Hl7OutboundBuilder {

    public static final Set<String> ORDER_TYPES = Set.of("ORM^O01", "OML^O21", "ADT^A04", "ADT^A08");
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** Sender / receiver identity of the MSH (from the instance's P4 {@code msh} parameters, reversed for outbound). */
    public record Header(String sendingApplication, String sendingFacility, String receivingApplication, String receivingFacility) {
    }

    /** One worklist entry, already minimised by the engine. */
    public record Order(String orderRef, String employeeId, String displayName, LocalDate dateOfBirth, String sex, Instant scheduledAt,
                        String category, String sessionId) {
    }

    private final HapiContext context;

    public Hl7OutboundBuilder() {
        this.context = new DefaultHapiContext(new CanonicalModelClassFactory("2.5"));
        this.context.setValidationContext(ValidationContextFactory.noValidation());
        // control ids come from the engine; HAPI's default file-based id generator would write an id_file into the working directory
        this.context.getParserConfiguration().setIdGenerator(new InMemoryIDGenerator());
    }

    /** @param messageType ORM^O01 | OML^O21 | ADT^A04 | ADT^A08 */
    public String order(String messageType, Header h, Order o, String controlId, Instant now) throws HL7Exception {
        return switch (messageType) {
            case "ORM^O01" -> orm(h, o, controlId, now);
            case "OML^O21" -> oml(h, o, controlId, now);
            case "ADT^A04", "ADT^A08" -> adt(messageType.substring(4), h, o, controlId, now);
            default -> throw new HL7Exception("Unsupported outbound message type " + messageType);
        };
    }

    String orm(Header h, Order o, String controlId, Instant now) throws HL7Exception {
        ORM_O01 m = new ORM_O01();
        m.setParser(context.getPipeParser());
        init(m, "ORM", "O01");
        msh(m.getMSH(), h, controlId, now);
        pid(m.getPATIENT().getPID(), o);
        pv1(m.getPATIENT().getPATIENT_VISIT().getPV1());
        orc(m.getORDER().getORC(), o, now);
        obr(m.getORDER().getORDER_DETAIL().getOBR(), o);
        return m.encode();
    }

    String oml(Header h, Order o, String controlId, Instant now) throws HL7Exception {
        OML_O21 m = new OML_O21();
        m.setParser(context.getPipeParser());
        init(m, "OML", "O21");
        msh(m.getMSH(), h, controlId, now);
        pid(m.getPATIENT().getPID(), o);
        pv1(m.getPATIENT().getPATIENT_VISIT().getPV1());
        orc(m.getORDER().getORC(), o, now);
        obr(m.getORDER().getOBSERVATION_REQUEST().getOBR(), o);
        return m.encode();
    }

    String adt(String trigger, Header h, Order o, String controlId, Instant now) throws HL7Exception {
        ADT_A01 m = new ADT_A01();
        m.setParser(context.getPipeParser());
        init(m, "ADT", trigger);
        m.getMSH().getMessageType().getMessageStructure().setValue("ADT_A01");
        msh(m.getMSH(), h, controlId, now);
        m.getEVN().getEventTypeCode().setValue(trigger);
        m.getEVN().getRecordedDateTime().getTime().setValue(TS.format(now));
        pid(m.getPID(), o);
        pv1(m.getPV1());
        if (o.orderRef() != null) {
            m.getPV1().getVisitNumber().getIDNumber().setValue(o.orderRef());
        }
        return m.encode();
    }

    /** Original-mode query for results of a device in a time range (historic retrieval). */
    public String query(Header h, String deviceSerial, Instant from, Instant to, int maxResults, String queryId, String controlId, Instant now)
            throws HL7Exception {
        QRY_R02 m = new QRY_R02();
        m.setParser(context.getPipeParser());
        init(m, "QRY", "R02");
        msh(m.getMSH(), h, controlId, now);
        m.getQRD().getQueryDateTime().getTime().setValue(TS.format(now));
        m.getQRD().getQueryFormatCode().setValue("R");
        m.getQRD().getQueryPriority().setValue("I");
        m.getQRD().getQueryID().setValue(queryId);
        m.getQRD().getQuantityLimitedRequest().getQuantity().setValue(String.valueOf(maxResults));
        m.getQRD().getQuantityLimitedRequest().getUnits().getIdentifier().setValue("RD");
        m.getQRD().getWhoSubjectFilter(0).getIDNumber().setValue(deviceSerial == null ? "ALL" : deviceSerial);
        m.getQRD().getWhatSubjectFilter(0).getIdentifier().setValue("RES");
        m.getQRD().getWhatDepartmentDataCode(0).getIdentifier().setValue("ALL");
        m.getQRF().getWhereSubjectFilter(0).setValue(deviceSerial == null ? "ALL" : deviceSerial);
        m.getQRF().getWhenDataStartDateTime().getTime().setValue(TS.format(from));
        m.getQRF().getWhenDataEndDateTime().getTime().setValue(TS.format(to));
        return m.encode();
    }

    static void init(ca.uhn.hl7v2.model.AbstractMessage m, String type, String trigger) throws HL7Exception {
        try {
            m.initQuickstart(type, trigger, "P");
        } catch (java.io.IOException e) {
            throw new HL7Exception("Message initialisation failed", e);
        }
    }

    void msh(MSH msh, Header h, String controlId, Instant now) throws DataTypeException {
        msh.getSendingApplication().getNamespaceID().setValue(nz(h.sendingApplication(), "WELLNESSZ"));
        msh.getSendingFacility().getNamespaceID().setValue(nz(h.sendingFacility(), ""));
        msh.getReceivingApplication().getNamespaceID().setValue(nz(h.receivingApplication(), "DEVICE"));
        msh.getReceivingFacility().getNamespaceID().setValue(nz(h.receivingFacility(), ""));
        msh.getDateTimeOfMessage().getTime().setValue(TS.format(now));
        msh.getMessageControlID().setValue(controlId);
        msh.getAcceptAcknowledgmentType().setValue("AL");
        msh.getApplicationAcknowledgmentType().setValue("NE");
    }

    static void pid(PID pid, Order o) throws HL7Exception {
        pid.getSetIDPID().setValue("1");
        pid.getPatientIdentifierList(0).getIDNumber().setValue(o.employeeId());
        pid.getPatientIdentifierList(0).getAssigningAuthority().getNamespaceID().setValue("WELLNESSZ");
        pid.getPatientIdentifierList(0).getIdentifierTypeCode().setValue("EI");
        String[] names = o.displayName() == null ? new String[] {""} : o.displayName().strip().split("\\s+", 2);
        String given = names.length > 1 ? names[0] : "";
        String family = names.length > 1 ? names[1] : names[0];
        pid.getPatientName(0).getFamilyName().getSurname().setValue(family);
        pid.getPatientName(0).getGivenName().setValue(given);
        if (o.dateOfBirth() != null) {
            pid.getDateTimeOfBirth().getTime().setValue(o.dateOfBirth().format(DateTimeFormatter.BASIC_ISO_DATE));
        }
        if (o.sex() != null) {
            String s = o.sex().toUpperCase(Locale.ROOT);
            pid.getAdministrativeSex().setValue(s.startsWith("M") ? "M" : s.startsWith("F") ? "F" : "U");
        }
    }

    static void pv1(PV1 pv1) throws DataTypeException {
        pv1.getSetIDPV1().setValue("1");
        pv1.getPatientClass().setValue("O");
    }

    static void orc(ORC orc, Order o, Instant now) throws DataTypeException {
        orc.getOrderControl().setValue("NW");
        orc.getPlacerOrderNumber().getEntityIdentifier().setValue(o.orderRef());
        orc.getPlacerOrderNumber().getNamespaceID().setValue("WELLNESSZ");
        orc.getOrderStatus().setValue("SC");
        orc.getDateTimeOfTransaction().getTime().setValue(TS.format(now));
    }

    static void obr(OBR obr, Order o) throws DataTypeException {
        obr.getSetIDOBR().setValue("1");
        obr.getPlacerOrderNumber().getEntityIdentifier().setValue(o.orderRef());
        obr.getPlacerOrderNumber().getNamespaceID().setValue("WELLNESSZ");
        obr.getUniversalServiceIdentifier().getIdentifier().setValue(o.category() == null ? "DEVICE" : o.category());
        obr.getUniversalServiceIdentifier().getText().setValue(o.category() == null ? "Device measurement" : o.category() + " measurement");
        obr.getUniversalServiceIdentifier().getNameOfCodingSystem().setValue("WZ");
        if (o.scheduledAt() != null) {
            obr.getRequestedDateTime().getTime().setValue(TS.format(o.scheduledAt()));
        }
    }

    static String nz(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
