package com.wellnessz.e03.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.e03.support.CodeRef;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.message.ORM_O01;
import ca.uhn.hl7v2.util.idgenerator.InMemoryIDGenerator;

class Hl7MessagesTest {

    final Hl7Messages messages = new Hl7Messages();

    static Message parse(String er7) throws HL7Exception {
        try (HapiContext ctx = new DefaultHapiContext()) {
            ctx.getParserConfiguration().setIdGenerator(new InMemoryIDGenerator());
            return ctx.getPipeParser().parse(er7);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void ormO01CarriesOneOrderPerTestAndEscapesDelimiters() throws Exception {
        String er7 = messages.orm("ORD-2026-000001", "E1004", "Rami ^Ramp|", "physician1", "URGENT",
                List.of(new CodeRef("LOINC", "2345-7", "Glucose"), new CodeRef("LOINC", "4548-4", "HbA1c")), Instant.parse("2026-10-29T10:15:00Z"));
        ORM_O01 orm = (ORM_O01) parse(er7);
        assertThat(orm.getMSH().getMessageType().getMessageCode().getValue()).isEqualTo("ORM");
        assertThat(orm.getMSH().getVersionID().getVersionID().getValue()).isEqualTo("2.5");
        assertThat(orm.getPATIENT().getPID().getPatientIdentifierList(0).getIDNumber().getValue()).isEqualTo("E1004");
        assertThat(orm.getPATIENT().getPID().getPatientName(0).getFamilyName().getSurname().getValue()).isEqualTo("^Ramp|");
        assertThat(orm.getORDERReps()).isEqualTo(2);
        assertThat(orm.getORDER(1).getORDER_DETAIL().getOBR().getUniversalServiceIdentifier().getIdentifier().getValue()).isEqualTo("4548-4");
        assertThat(orm.getORDER(0).getORDER_DETAIL().getOBR().getPriorityOBR().getValue()).isEqualTo("S");
        assertThat(orm.getORDER(0).getORC().getPlacerOrderNumber().getEntityIdentifier().getValue()).isEqualTo("ORD-2026-000001");
    }

    @Test
    void oruR01ObservationsAreExtracted() throws Exception {
        String oru = "MSH|^~\\&|LABSYS|LAB|WELLNESSZ|OH|20261029101500||ORU^R01|MSG1|P|2.5\r"
                + "PID|1||E1004^^^HR||Ramp^Rami\r"
                + "OBR|1|ORD-2026-000001||2345-7^Glucose^LN\r"
                + "OBX|1|NM|2345-7^Glucose^LN||11.2|mmol/L|3.9-7.8|H|||F|||20261029090000\r"
                + "OBX|2|ST|625-4^Stool culture^LN||No pathogens||||||P\r";
        List<Hl7Messages.Observation> obs = messages.observations(parse(oru));
        assertThat(obs).hasSize(2);
        Hl7Messages.Observation glucose = obs.get(0);
        assertThat(glucose.employeeId()).isEqualTo("E1004");
        assertThat(glucose.placerOrderNumber()).isEqualTo("ORD-2026-000001");
        assertThat(glucose.code()).isEqualTo(new CodeRef("LOINC", "2345-7", "Glucose"));
        assertThat(glucose.value()).isEqualTo("11.2");
        assertThat(glucose.unit()).isEqualTo("mmol/L");
        assertThat(glucose.referenceRange()).isEqualTo("3.9-7.8");
        assertThat(glucose.interpretation()).isEqualTo("H");
        assertThat(glucose.status()).isEqualTo("FINAL");
        assertThat(glucose.effectiveAt()).isNotNull();
        assertThat(obs.get(1).status()).isEqualTo("PRELIMINARY");
        assertThat(obs.get(1).value()).isEqualTo("No pathogens");
    }

    @Test
    void otherMessageTypesAreRejected() throws Exception {
        String adt = "MSH|^~\\&|HR|HR|WELLNESSZ|OH|20261029101500||ADT^A01|MSG2|P|2.5\rEVN|A01|20261029101500\rPID|1||E1||X^Y\rPV1|1|O\r";
        Message m = parse(adt);
        assertThatThrownBy(() -> messages.observations(m)).isInstanceOf(HL7Exception.class);
        assertThat(Hl7Messages.system("LN")).isEqualTo("LOINC");
        assertThat(Hl7Messages.system("XYZ")).isEqualTo("LOCAL");
        assertThat(Hl7Messages.status("C")).isEqualTo("AMENDED");
    }
}
