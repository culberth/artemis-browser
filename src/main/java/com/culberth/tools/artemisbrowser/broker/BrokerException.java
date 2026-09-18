package com.culberth.tools.artemisbrowser.broker;

/** Anything that went wrong talking to the broker, with a message fit to show a user. */
public class BrokerException extends RuntimeException {

    public BrokerException(String message) {
        super(message);
    }

    public BrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
