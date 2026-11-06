package io.intimed;

import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.parse.ParseException;
import io.intimed.engine.parse.Parsed;
import io.intimed.engine.parse.RecordParser;
import io.intimed.engine.parse.XmlParser;
import io.intimed.engine.sim.Simulator;
import io.intimed.engine.transport.AstmE1381;
import io.intimed.engine.transport.Poct1aConversation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.intimed.engine.transport.AstmE1381.ACK;
import static io.intimed.engine.transport.AstmE1381.ENQ;
import static io.intimed.engine.transport.AstmE1381.EOT;
import static io.intimed.engine.transport.AstmE1381.NAK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IM-E01-BL-007 protocol codecs: ASTM E1381 low-level framing and the POCT1-A2 conversation state machine. */
class ProtocolCodecTests {

    private static DriverPackage driver(String file) {
        return new DriverPackage(Json.resource("drivers/" + file), "CERTIFIED", "VALID", "test");
    }

    private static String s(byte[] b) { return new String(b, StandardCharsets.ISO_8859_1); }

    // ------------------------------------------------------------------ ASTM E1381
    @Test
    void goldenFrameAndChecksum() {
        byte[] f = AstmE1381.frame(1, "H|\\^&\r", true);
        // STX '1' H|\^& CR ETX 'E' '5' CR LF – checksum 0xE5 = sum('1'…ETX) mod 256 (worked by hand)
        assertThat(s(f)).isEqualTo("\u00021H|\\^&\r\u0003E5\r\n");
        AstmE1381.Frame p = AstmE1381.parseFrame(f);
        assertThat(p.number()).isEqualTo(1);
        assertThat(p.text()).isEqualTo("H|\\^&\r");
        assertThat(p.last()).isTrue();
        byte[] header = "1H|\\^&|||cobas^8000^SN80041|||||||P|1|20261101120000\r\u0003".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(AstmE1381.checksum(header, 0, header.length - 1)).isEqualTo("10");
    }

    @Test
    void longRecordsAreSplitAt240CharactersWithEtbAndFrameNumbersWrapModulo8() {
        String longRec = "R|1|^^^GLU|" + "9".repeat(580);
        List<byte[]> fs = AstmE1381.frames("H|\\^&\r" + longRec + "\rL|1|N\r");
        // H (1 frame) + 593-char record incl. CR (3 frames: 240 + 240 + 113) + L (1 frame)
        assertThat(fs).hasSize(5);
        assertThat(fs.get(1)[fs.get(1).length - 5]).isEqualTo((byte) AstmE1381.ETB);
        assertThat(fs.get(2)[fs.get(2).length - 5]).isEqualTo((byte) AstmE1381.ETB);
        assertThat(fs.get(3)[fs.get(3).length - 5]).isEqualTo((byte) AstmE1381.ETX);
        assertThat(fs.get(1).length).isEqualTo(AstmE1381.MAX_FRAME);
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < 10; i++) msg.append("C|").append(i).append("|note\r");
        List<Integer> numbers = new ArrayList<>();
        for (byte[] f : AstmE1381.frames(msg.toString())) numbers.add(AstmE1381.parseFrame(f).number());
        assertThat(numbers).containsExactly(1, 2, 3, 4, 5, 6, 7, 0, 1, 2);
    }

    @Test
    void receiverReassemblesMultiFrameMessagesNaksBadChecksumsAndDropsRetransmittedDuplicates() {
        String longRec = "R|1|^^^GLU|" + "7".repeat(400);
        String message = "H|\\^&\r" + longRec + "\rL|1|N\r";
        List<byte[]> fs = AstmE1381.frames(message);
        AstmE1381.Receiver r = new AstmE1381.Receiver();
        assertThat(r.accept(ENQ).reply()).isEqualTo(ACK);
        String done = null;
        for (int i = 0; i < fs.size(); i++) {
            byte[] f = fs.get(i);
            if (i == 1) {
                byte[] bad = f.clone();
                bad[5] = (byte) (bad[5] == '7' ? '8' : '7'); // corrupt one text byte → checksum mismatch
                assertThat(feed(r, bad)).isEqualTo(NAK);
                assertThat(r.lastError()).contains("checksum");
            }
            assertThat(feed(r, f)).isEqualTo(ACK);
            if (i == 2) assertThat(feed(r, f)).as("retransmission of an accepted frame").isEqualTo(ACK);
        }
        AstmE1381.Receiver.Step end = r.accept(EOT);
        done = end.message();
        assertThat(done).isEqualTo(message);
    }

    @Test
    void receiverRefusesOversizeFramesAndWrongFrameNumbers() {
        AstmE1381.Receiver r = new AstmE1381.Receiver();
        r.accept(ENQ);
        byte[] big = new byte[AstmE1381.MAX_FRAME + 10];
        java.util.Arrays.fill(big, (byte) 'A');
        big[0] = AstmE1381.STX;
        int reply = -1;
        for (byte b : big) { int x = r.accept(b & 0xFF).reply(); if (x >= 0) reply = x; }
        assertThat(reply).isEqualTo(NAK);
        assertThat(feed(r, AstmE1381.frame(3, "H|\\^&\r", true))).isEqualTo(NAK);
        assertThat(r.lastError()).contains("expected 1");
        assertThatThrownBy(() -> AstmE1381.parseFrame("\u00021H|\r\u0003ZZ\r\n".getBytes(StandardCharsets.ISO_8859_1))).hasMessageContaining("checksum");
        assertThatThrownBy(() -> AstmE1381.frame(1, "x".repeat(241), true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void senderRetransmitsUpToSixTimesThenAbortsWithEot() {
        AstmE1381.Sender snd = new AstmE1381.Sender("H|\\^&\rL|1|N\r");
        assertThat(snd.start()).containsExactly(ENQ);
        byte[] first = snd.onReply(ACK);
        assertThat(AstmE1381.parseFrame(first).number()).isEqualTo(1);
        for (int i = 0; i < AstmE1381.MAX_RETRANSMIT; i++) assertThat(snd.onReply(NAK)).as("retransmission %d", i + 1).isEqualTo(first);
        assertThat(snd.onReply(NAK)).containsExactly(EOT);
        assertThat(snd.state()).isEqualTo(AstmE1381.Sender.State.FAILED);
        assertThat(snd.failure()).contains("6 times");
        AstmE1381.Sender t = new AstmE1381.Sender("H|\\^&\r");
        t.start();
        t.onReply(ACK);
        assertThat(t.onTimeout()).containsExactly(EOT);
        assertThat(t.state()).isEqualTo(AstmE1381.Sender.State.FAILED);
    }

    @Test
    void senderAndReceiverInterworkOverAStreamAndTheAstmParserAcceptsTheResult() throws Exception {
        DriverPackage d = driver("lab.roche.cobas-astm-1.0.0.json");
        String msg = Simulator.render(d, Simulator.values(d), "MRN-ASTM-1", Instant.parse("2026-11-01T12:00:00Z"), "SN80041");
        AstmE1381.Sender snd = new AstmE1381.Sender(msg);
        AstmE1381.Receiver rcv = new AstmE1381.Receiver();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        byte[] out = snd.start();
        String received = null;
        int guard = 0;
        while (snd.state() != AstmE1381.Sender.State.DONE && snd.state() != AstmE1381.Sender.State.FAILED && guard++ < 1000) {
            wire.write(out);
            int reply = -1;
            for (byte b : out) {
                AstmE1381.Receiver.Step st = rcv.accept(b & 0xFF);
                if (st.reply() >= 0) reply = st.reply();
                if (st.message() != null) received = st.message();
            }
            if (reply < 0) break;
            out = snd.onReply(reply);
        }
        wire.write(out);
        for (byte b : out) { AstmE1381.Receiver.Step st = rcv.accept(b & 0xFF); if (st.message() != null) received = st.message(); }
        assertThat(snd.state()).isEqualTo(AstmE1381.Sender.State.DONE);
        assertThat(received).isEqualTo(msg.replace("\r\n", "\r"));
        // the raw capture (ENQ, frames, EOT) is accepted by the ASTM parser directly: unframed and checksum-verified
        List<Parsed> parsed = new RecordParser(false).parse(d.grammar(), wire.toString(StandardCharsets.ISO_8859_1));
        assertThat(parsed.get(0).values).containsKey("glucose");
        // stream helper
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(wire.toByteArray());
        ByteArrayOutputStream acks = new ByteArrayOutputStream();
        assertThat(new AstmE1381.Receiver().receive(in, acks)).isEqualTo(received);
        assertThat(acks.toByteArray()).containsOnly((byte) ACK);
        // a corrupted capture is refused by the parser
        byte[] bad = wire.toByteArray();
        for (int i = 0; i < bad.length; i++) if (bad[i] == 'G' && bad[i + 1] == 'L') { bad[i] = 'X'; break; }
        assertThatThrownBy(() -> new RecordParser(false).parse(d.grammar(), new String(bad, StandardCharsets.ISO_8859_1))).isInstanceOf(ParseException.class).hasMessageContaining("E1381");
    }

    // ------------------------------------------------------------------ POCT1-A2
    @Test
    void poct1aConversationHelloStatusObservationsEndOfTopicAndEnd() {
        DriverPackage d = driver("bloodgas.radiometer.abl90-poct1a-1.0.0.json");
        Poct1aConversation c = new Poct1aConversation();
        List<String> r = c.onMessage(Poct1aConversation.deviceMessage("HEL.R01", "1", "<DEV><DEV.device_id V=\"ABL90-1\"/></DEV>"));
        assertThat(r).hasSize(1);
        assertThat(Poct1aConversation.ackCode(r.get(0))).isEqualTo("AA");
        assertThat(r.get(0)).contains("<ACK.ack_control_id V=\"1\"/>");
        assertThat(c.deviceId()).isEqualTo("ABL90-1");
        r = c.onMessage(Poct1aConversation.deviceMessage("DST.R01", "2", "<DST><DST.new_observations_qty V=\"1\"/></DST>"));
        assertThat(r).hasSize(2);
        assertThat(r.get(1)).startsWith("<REQ.R01>").contains("ROBS");
        assertThat(c.state()).isEqualTo(Poct1aConversation.State.READY);
        String obs = Simulator.render(d, Simulator.values(d), "MRN-POCT-1", Instant.parse("2026-11-01T12:00:00Z"), "ABL90-1");
        r = c.onMessage(obs);
        assertThat(Poct1aConversation.ackCode(r.get(0))).isEqualTo("AA");
        // retransmission after a lost ACK: acknowledged again, collected once
        r = c.onMessage(obs);
        assertThat(Poct1aConversation.ackCode(r.get(0))).isEqualTo("AA");
        assertThat(c.observations()).hasSize(1);
        r = c.onMessage(Poct1aConversation.deviceMessage("EOT.R01", "9001", "<EOT><EOT.topic_cd V=\"OBS\"/></EOT>"));
        assertThat(r).hasSize(2);
        assertThat(r.get(1)).startsWith("<END.R01>");
        assertThat(c.state()).isEqualTo(Poct1aConversation.State.TERMINATED);
        assertThat(Poct1aConversation.ackCode(c.onMessage(Poct1aConversation.deviceMessage("OBS.R01", "9002", "")).get(0))).isEqualTo("AE");
        // collected OBS is what the POCT1A parser + driver grammar consume
        List<Parsed> p = new XmlParser("POCT1A").parse(d.grammar(), c.observations().get(0));
        assertThat(p.get(0).values).containsKey("ph");
    }

    @Test
    void poct1aRejectsOutOfOrderMalformedAndXxeMessages() {
        Poct1aConversation c = new Poct1aConversation();
        assertThat(Poct1aConversation.ackCode(c.onMessage(Poct1aConversation.deviceMessage("OBS.R01", "1", "")).get(0))).as("OBS before HEL").isEqualTo("AE");
        assertThat(c.state()).isEqualTo(Poct1aConversation.State.AWAIT_HELLO);
        assertThat(Poct1aConversation.ackCode(c.onMessage("<HEL.R01><broken").get(0))).isEqualTo("AE");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><HEL.R01><HDR><HDR.message_type V=\"HEL.R01\"/><HDR.control_id V=\"&e;\"/><HDR.version_id V=\"POCT1\"/></HDR></HEL.R01>";
        assertThat(Poct1aConversation.ackCode(c.onMessage(xxe).get(0))).isEqualTo("AE");
        assertThat(Poct1aConversation.ackCode(c.onMessage("<HEL.R01><HDR><HDR.message_type V=\"DST.R01\"/><HDR.control_id V=\"5\"/><HDR.version_id V=\"POCT1\"/></HDR></HEL.R01>").get(0)))
                .as("root/type mismatch").isEqualTo("AE");
        assertThat(Poct1aConversation.ackCode(c.onMessage(Poct1aConversation.deviceMessage("HEL.R01", "6", "")).get(0))).isEqualTo("AA");
        assertThat(Poct1aConversation.ackCode(c.onMessage(Poct1aConversation.deviceMessage("HEL.R01", "7", "")).get(0))).as("second HEL").isEqualTo("AE");
        // the rejected control id may be reused once the state allows it
        assertThat(Poct1aConversation.ackCode(c.onMessage(Poct1aConversation.deviceMessage("DST.R01", "7", "<DST><DST.new_observations_qty V=\"0\"/></DST>")).get(0))).isEqualTo("AA");
        assertThat(c.onMessage(Poct1aConversation.deviceMessage("END.R01", "8", "")).get(0)).contains("AA");
        assertThat(c.state()).isEqualTo(Poct1aConversation.State.TERMINATED);
        assertThat(c.errors()).isNotEmpty();
        assertThat(Poct1aConversation.ackCode(c.onMessage("x".repeat(Poct1aConversation.MAX_MESSAGE + 1)).get(0))).isEqualTo("AE");
    }

    private static int feed(AstmE1381.Receiver r, byte[] frame) {
        int reply = -1;
        for (byte b : frame) { int x = r.accept(b & 0xFF).reply(); if (x >= 0) reply = x; }
        return reply;
    }
}
