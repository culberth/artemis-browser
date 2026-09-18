package com.culberth.tools.artemisbrowser.broker;

/** Raised when a request needs a broker connection and this HTTP session has none. */
public class NotConnectedException extends BrokerException
{

    public NotConnectedException()
    {
        super("Not connected to a broker.");
    }
}
