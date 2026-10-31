package com.wellnessz.gateway.connector.mllp;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Minimal Lower Layer Protocol (HL7 v2 Implementation Guide, Appendix C): {@code <VT> message <FS><CR>} with
 * VT = 0x0B, FS = 0x1C, CR = 0x0D.
 */
public final class MllpFraming {

    public static final int START = 0x0B;
    public static final int END = 0x1C;
    public static final int CR = 0x0D;

    private MllpFraming() {
    }

    public static byte[] encode(byte[] message) {
        byte[] out = new byte[message.length + 3];
        out[0] = START;
        System.arraycopy(message, 0, out, 1, message.length);
        out[out.length - 2] = END;
        out[out.length - 1] = CR;
        return out;
    }

    /** Raised when a frame exceeds the configured maximum; the connection must be closed. */
    public static class FrameTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        public FrameTooLargeException(int max) {
            super("MLLP frame larger than " + max + " bytes");
        }
    }

    /** Reads successive frames from a stream. Bytes outside a frame (line noise, keep-alives) are discarded. */
    public static final class Reader {
        private final InputStream in;
        private final int maxBytes;

        public Reader(InputStream in, int maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        /** @return the next message without framing, or {@code null} at a clean end of stream between frames */
        public byte[] next() throws IOException {
            int b;
            do {
                b = in.read();
                if (b == -1) {
                    return null;
                }
            } while (b != START);
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (true) {
                b = in.read();
                if (b == -1) {
                    throw new EOFException("connection closed inside an MLLP frame");
                }
                if (b == END) {
                    int next = in.read();
                    if (next == CR) {
                        return msg.toByteArray();
                    }
                    if (next == -1) {
                        throw new EOFException("connection closed inside an MLLP frame");
                    }
                    if (next == START) {
                        // a new frame started without a proper trailer: drop the broken one
                        msg.reset();
                        continue;
                    }
                    msg.write(b);
                    b = next;
                } else if (b == START) {
                    msg.reset(); // restart: the previous frame was never terminated
                    continue;
                }
                msg.write(b);
                if (msg.size() > maxBytes) {
                    throw new FrameTooLargeException(maxBytes);
                }
            }
        }
    }
}
