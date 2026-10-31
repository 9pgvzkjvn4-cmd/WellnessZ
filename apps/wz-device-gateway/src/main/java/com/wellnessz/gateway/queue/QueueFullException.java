package com.wellnessz.gateway.queue;

/** The queue reached its capacity: the connector must refuse the message (negative ACK) so the device keeps it. */
public class QueueFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QueueFullException(String message) {
        super(message);
    }
}
