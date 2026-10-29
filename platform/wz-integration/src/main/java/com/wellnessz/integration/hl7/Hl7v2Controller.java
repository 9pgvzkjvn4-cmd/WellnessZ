package com.wellnessz.integration.hl7;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.integration.inbound.InboundService;

import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.HL7Exception;

/**
 * HL7 v2 over HTTPS (instead of plaintext MLLP). Returns an ER7 ACK: AA when accepted, AR/400 when unparseable.
 * For legacy MLLP senders, terminate MLLP+TLS at the integration engine / API gateway and forward here.
 */
@RestController
@RequestMapping("/api/v1/integration/hl7v2")
public class Hl7v2Controller {

    private static final String ER7 = "x-application/hl7-v2+er7";
    private final Hl7v2Service hl7;
    private final InboundService inbound;

    public Hl7v2Controller(Hl7v2Service hl7, InboundService inbound) {
        this.hl7 = hl7;
        this.inbound = inbound;
    }

    @PostMapping(consumes = {ER7, "text/plain"}, produces = "text/plain")
    public ResponseEntity<String> receive(@RequestBody String er7) {
        Hl7v2Service.ParsedMessage parsed;
        try {
            parsed = hl7.parse(er7);
        } catch (HL7Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("MSA|AR||Unparseable HL7 v2 message");
        }
        inbound.accept("hl7v2:" + parsed.sendingApplication(), "HL7V2", parsed.messageType(), parsed.controlId(), er7);
        return ResponseEntity.ok(hl7.ack(parsed.message(), AcknowledgmentCode.AA, null));
    }
}
