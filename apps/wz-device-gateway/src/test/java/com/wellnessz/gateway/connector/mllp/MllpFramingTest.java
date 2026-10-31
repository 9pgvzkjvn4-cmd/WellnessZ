package com.wellnessz.gateway.connector.mllp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class MllpFramingTest {

    static final String ORU = "MSH|^~\\&|CONNEX|LHR-OH|WZ|WELLNESSZ|20261031101500||ORU^R01^ORU_R01|MSG00042|P|2.6\r"
            + "PID|1||SYN-0001^^^WZ||Synthetic^Pat\r"
            + "OBX|1|NM|8867-4^Heart rate^LN||72|/min|||||F\r";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void encodeWrapsMessageInStartAndEndBlock() {
        byte[] framed = MllpFraming.encode(bytes("MSH|x"));
        assertThat(framed[0]).isEqualTo((byte) 0x0B);
        assertThat(framed[framed.length - 2]).isEqualTo((byte) 0x1C);
        assertThat(framed[framed.length - 1]).isEqualTo((byte) 0x0D);
        assertThat(new String(framed, 1, framed.length - 3, StandardCharsets.ISO_8859_1)).isEqualTo("MSH|x");
    }

    @Test
    void readerRoundTripsSeveralFramesAndDiscardsNoiseBetweenFrames() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(bytes("\r\nnoise"));
        stream.write(MllpFraming.encode(bytes(ORU)));
        stream.write(bytes("\n"));
        stream.write(MllpFraming.encode(bytes("MSH|^~\\&|B|||||||2|P|2.5\r")));
        MllpFraming.Reader r = new MllpFraming.Reader(new ByteArrayInputStream(stream.toByteArray()), 1 << 20);
        assertThat(new String(r.next(), StandardCharsets.ISO_8859_1)).isEqualTo(ORU);
        assertThat(new String(r.next(), StandardCharsets.ISO_8859_1)).startsWith("MSH|^~\\&|B|");
        assertThat(r.next()).isNull();
    }

    @Test
    void fsNotFollowedByCrIsTreatedAsData() throws Exception {
        byte[] stream = {0x0B, 'A', 0x1C, 'B', 0x1C, 0x0D};
        byte[] msg = new MllpFraming.Reader(new ByteArrayInputStream(stream), 100).next();
        assertThat(msg).containsExactly('A', 0x1C, 'B');
    }

    @Test
    void newStartBlockDropsAnUnterminatedFrame() throws Exception {
        byte[] stream = {0x0B, 'X', 'X', 0x0B, 'O', 'K', 0x1C, 0x0D};
        assertThat(new MllpFraming.Reader(new ByteArrayInputStream(stream), 100).next()).containsExactly('O', 'K');
    }

    @Test
    void oversizedFrameIsRejected() {
        byte[] big = MllpFraming.encode(new byte[200]);
        MllpFraming.Reader r = new MllpFraming.Reader(new ByteArrayInputStream(big), 100);
        assertThatThrownBy(r::next).isInstanceOf(MllpFraming.FrameTooLargeException.class);
    }

    @Test
    void endOfStreamInsideFrameIsAnError() {
        byte[] stream = {0x0B, 'M', 'S', 'H'};
        assertThatThrownBy(() -> new MllpFraming.Reader(new ByteArrayInputStream(stream), 100).next())
                .isInstanceOf(EOFException.class);
    }

    @Test
    void headerIsParsedFromMsh() {
        Hl7Header h = Hl7Header.parse(bytes(ORU));
        assertThat(h).isNotNull();
        assertThat(h.sendingApplicationId()).isEqualTo("CONNEX");
        assertThat(h.controlId()).isEqualTo("MSG00042");
        assertThat(h.triggerEvent()).isEqualTo("R01");
        assertThat(h.version()).isEqualTo("2.6");
        assertThat(Hl7Header.parse(bytes("PID|1"))).isNull();
    }

    @Test
    void localAckEchoesControlIdAndCannotBeUsedForSegmentInjection() {
        Hl7Header h = Hl7Header.parse(bytes(ORU));
        byte[] ack = Hl7Ack.build(h, "AA", "Stored\rMSA|AR|evil", Instant.parse("2026-10-31T10:15:00Z"));
        String text = new String(ack, StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("MSH|^~\\&|WZ-GATEWAY|WELLNESSZ|CONNEX|LHR-OH|20261031101500||ACK^R01^ACK|");
        assertThat(text).contains("\rMSA|AA|MSG00042|StoredMSAARevil\r");
        assertThat(text.split("\r")).hasSize(2);
        assertThat(Hl7Ack.acknowledgementCode(ack)).isEqualTo("AA");
    }
}
