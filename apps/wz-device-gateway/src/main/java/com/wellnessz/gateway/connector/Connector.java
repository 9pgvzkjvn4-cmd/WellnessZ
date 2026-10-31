package com.wellnessz.gateway.connector;

/** A device-facing connector (listener, watcher, poller, serial reader). */
public interface Connector {

    String id();

    /** Connectivity option family, e.g. {@code MLLP}, {@code FOLDER}, {@code SERIAL} (PHI-free metric label). */
    String type();

    void start() throws Exception;

    void stop();

    ConnectorStatus status();
}
