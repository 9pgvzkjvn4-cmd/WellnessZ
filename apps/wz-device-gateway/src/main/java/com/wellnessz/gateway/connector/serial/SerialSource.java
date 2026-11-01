package com.wellnessz.gateway.connector.serial;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Byte source of a serial port. The gateway ships serial-over-TCP (terminal servers such as Moxa NPort, Lantronix or
 * ser2net in raw TCP mode) and the stdin / file simulation; a native RS-232 / USB-CDC implementation plugs in here.
 */
public interface SerialSource extends AutoCloseable {

    /** Opens (or re-opens) the port and returns its input stream. */
    InputStream open() throws IOException;

    /** {@code true} when the stream ends for good at EOF (simulation) rather than being reconnected. */
    boolean finite();

    @Override
    void close() throws IOException;

    /**
     * Writes bytes to the device (start-from-form command, E31-BL-019). Sources without a transmit path (the replay simulation)
     * refuse; a native port implementation writes to the port.
     */
    default void write(byte[] bytes) throws IOException {
        throw new IOException("the serial source has no transmit path");
    }

    /** {@code true} when {@link #write(byte[])} can reach the device. */
    default boolean writable() {
        return false;
    }

    /** Serial-over-TCP client. */
    final class Tcp implements SerialSource {
        private final String host;
        private final int port;
        private Socket socket;

        public Tcp(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        @SuppressFBWarnings(value = "UNENCRYPTED_SOCKET",
                justification = "Serial terminal servers speak raw TCP; the connection is only allowed to a loopback "
                        + "address or under the documented segmented-VLAN exception (checked by ConnectorRegistry) and "
                        + "is reported as a hardening finding. Frames are queued encrypted at once.")
        public InputStream open() throws IOException {
            close();
            socket = new Socket();
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(host, port), 10_000);
            return socket.getInputStream();
        }

        @Override
        public boolean finite() {
            return false;
        }

        @Override
        public synchronized void write(byte[] bytes) throws IOException {
            Socket s = socket;
            if (s == null || s.isClosed()) {
                throw new IOException("serial terminal server not connected");
            }
            s.getOutputStream().write(bytes);
            s.getOutputStream().flush();
        }

        @Override
        public boolean writable() {
            return true;
        }

        @Override
        public void close() throws IOException {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        }
    }

    /** Simulation: replays a captured byte stream from a file ({@code --simulate-serial <file>}) or stdin. */
    final class Stream implements SerialSource {
        private final Path file;
        private InputStream in;

        /** @param file the capture to replay, or {@code null} for standard input */
        public Stream(Path file) {
            this.file = file;
        }

        @Override
        public InputStream open() throws IOException {
            close();
            in = file == null ? System.in : Files.newInputStream(file);
            return in;
        }

        @Override
        public boolean finite() {
            return true;
        }

        @Override
        public void close() throws IOException {
            if (in != null && file != null) {
                in.close();
            }
            in = null;
        }
    }
}
