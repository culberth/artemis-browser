package com.culberth.tools.artemisbrowser.broker;

/**
 * Whether one message is in flight on one queue.
 *
 * @param checked false when the queue had more in flight than {@code artemis.in-flight-limit}, so its delivering list
 *                was not read and the answer is unknown
 * @param holder  the consumer holding it, matched to its client where possible; null when it is not in flight here
 */
public record InFlightLookup(String queueName, boolean checked, InFlightConsumer holder, InFlightMessage message)
{

    public static InFlightLookup notChecked(String queueName)
    {
        return new InFlightLookup(queueName, false, null, null);
    }

    public static InFlightLookup notInFlight(String queueName)
    {
        return new InFlightLookup(queueName, true, null, null);
    }

    public boolean found()
    {
        return holder != null;
    }
}
