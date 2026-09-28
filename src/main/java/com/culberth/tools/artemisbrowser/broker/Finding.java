package com.culberth.tools.artemisbrowser.broker;

/**
 * One observation about why something on this broker may not be moving.
 *
 * <p>
 * Deliberately an observation rather than a verdict. Every finding says what was actually read — this count, that flag
 * — and what it usually means, because the tool can see the broker's state but not the intent behind it. A queue with
 * no consumer is stuck if something should be reading it and perfectly normal if nothing is meant to be running yet,
 * and only the person reading the page knows which.
 *
 * @param severity     {@link #STUCK} for something that is not moving now, {@link #WATCH} for something that will bite
 *                     later or may be fine
 * @param queue        the queue to open to see more, or null when the finding is about the broker or an address
 * @param address      the address to open to see more, or null; a finding about a subscription carries both
 * @param clientId     the client the finding is about, when it has a client id; else null
 * @param connectionId the client's connection, for one with no client id; else null
 */
public record Finding(String severity, String title, String detail, String queue, String address, String clientId,
        String connectionId)
{

    public Finding(String severity, String title, String detail, String queue, String address)
    {
        this(severity, title, detail, queue, address, null, null);
    }

    /** The same finding, linked to the client it names — by client id where there is one, else by connection. */
    public Finding aboutClient(String clientId, String connectionId)
    {
        boolean named = clientId != null && !clientId.isEmpty();
        return new Finding(severity, title, detail, queue, address, named ? clientId : null,
                named ? null : connectionId);
    }

    public boolean hasClient()
    {
        return clientId != null || connectionId != null;
    }

    public static final String STUCK = "stuck";
    public static final String WATCH = "watch";

    public static Finding stuck(String title, String detail, String queue)
    {
        return new Finding(STUCK, title, detail, queue, null);
    }

    public static Finding watch(String title, String detail, String queue)
    {
        return new Finding(WATCH, title, detail, queue, null);
    }

    public static Finding atAddress(String severity, String title, String detail, String address)
    {
        return new Finding(severity, title, detail, null, address);
    }

    public boolean isStuck()
    {
        return STUCK.equals(severity);
    }
}
