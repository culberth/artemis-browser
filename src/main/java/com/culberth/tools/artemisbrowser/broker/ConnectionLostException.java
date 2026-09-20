package com.culberth.tools.artemisbrowser.broker;

/**
 * The connection to the broker has gone — the broker restarted, the network dropped, or the session was closed under
 * us.
 *
 * <p>
 * Deliberately <em>not</em> a {@link BrokerException}, though it is tempting: the controllers catch that one to show an
 * error beside the page they were building, which is right for a rejected operation or a bad filter and wrong here. A
 * lost connection means nothing on that page can work, so it has to travel past those handlers to the one that sends
 * the user back to the connect form. Telling the two apart is the difference between "Session is closed" — true,
 * unhelpful, and indistinguishable from a bug in this tool — and "the broker went away, here is the connect form".
 */
public class ConnectionLostException extends RuntimeException
{

    public ConnectionLostException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
