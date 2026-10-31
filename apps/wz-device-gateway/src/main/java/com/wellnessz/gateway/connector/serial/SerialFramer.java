package com.wellnessz.gateway.connector.serial;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import com.wellnessz.gateway.config.GatewayProperties.Framing;

/**
 * Edge framing of serial byte streams per the driver's {@code protocol.framing} (parsing happens centrally):
 * {@code LINE} (CR, LF or CRLF terminated), {@code STX_ETX} (0x02 … 0x03, bytes outside a frame are discarded; a
 * trailing block check character, if any, stays with the next noise and is dropped) and {@code TERMINATOR} (a single
 * configurable terminator byte).
 */
public final class SerialFramer {

    public static final int STX = 0x02;
    public static final int ETX = 0x03;

    private final InputStream in;
    private final Framing framing;
    private final int terminator;
    private final int maxBytes;

    public SerialFramer(InputStream in, Framing framing, int terminator, int maxBytes) {
        this.in = in;
        this.framing = framing;
        this.terminator = terminator;
        this.maxBytes = maxBytes;
    }

    /** @return the next frame (without delimiters), or {@code null} at end of stream; partial trailing data is returned */
    public byte[] next() throws IOException {
        return switch (framing) {
            case LINE -> line();
            case STX_ETX -> stxEtx();
            case TERMINATOR -> until(terminator);
        };
    }

    private byte[] line() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r' || b == '\n') {
                if (buf.size() > 0) {
                    return buf.toByteArray();
                }
                continue; // empty line / second half of CRLF
            }
            append(buf, b);
        }
        return buf.size() > 0 ? buf.toByteArray() : null;
    }

    private byte[] stxEtx() throws IOException {
        int b;
        do {
            b = in.read();
            if (b == -1) {
                return null;
            }
        } while (b != STX);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while ((b = in.read()) != -1) {
            if (b == ETX) {
                return buf.toByteArray();
            }
            if (b == STX) {
                buf.reset(); // unterminated frame: restart
                continue;
            }
            append(buf, b);
        }
        return null; // incomplete frame at end of stream is discarded
    }

    private byte[] until(int term) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == term) {
                return buf.toByteArray();
            }
            append(buf, b);
        }
        return buf.size() > 0 ? buf.toByteArray() : null;
    }

    private void append(ByteArrayOutputStream buf, int b) throws IOException {
        buf.write(b);
        if (buf.size() > maxBytes) {
            throw new IOException("serial frame larger than " + maxBytes + " bytes");
        }
    }
}
