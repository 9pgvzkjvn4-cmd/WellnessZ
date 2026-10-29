package com.wellnessz.integration.hl7;

import org.springframework.stereotype.Service;

import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.idgenerator.InMemoryIDGenerator;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/** Parses HL7 v2.x ER7 messages (ORU^R01 results, ORM/OML orders, ADT) and builds MSH-based acknowledgements. */
@Service
public class Hl7v2Service {

    private final HapiContext context;

    public Hl7v2Service() {
        this.context = new DefaultHapiContext();
        this.context.setValidationContext(ValidationContextFactory.defaultValidation());
        // HAPI's default ACK control-id generator persists to ./id_file – not allowed on a read-only root filesystem
        this.context.getParserConfiguration().setIdGenerator(new InMemoryIDGenerator());
    }

    public ParsedMessage parse(String er7) throws HL7Exception {
        // normalise line endings to HL7 segment separator
        String normalised = er7.replace("\r\n", "\r").replace('\n', '\r');
        PipeParser parser = context.getPipeParser();
        Message message = parser.parse(normalised);
        Terser terser = new Terser(message);
        String type = terser.get("/MSH-9-1") + "^" + terser.get("/MSH-9-2");
        String controlId = terser.get("/MSH-10");
        String sendingApp = terser.get("/MSH-3-1");
        return new ParsedMessage(message, type, controlId, sendingApp);
    }

    public String ack(Message message, AcknowledgmentCode code, String text) {
        try {
            Message ack = message.generateACK(code, code == AcknowledgmentCode.AA ? null : new HL7Exception(text));
            return ack.encode();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build HL7 ACK", e);
        }
    }

    public record ParsedMessage(Message message, String messageType, String controlId, String sendingApplication) {
    }
}
