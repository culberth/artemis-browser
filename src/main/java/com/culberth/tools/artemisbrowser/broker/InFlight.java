package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * What a queue has delivered to its consumers and not had acknowledged.
 *
 * @param deliveringCount what the queue reported as in flight when this was read
 * @param limit           the most this tool will list; see {@code artemis.in-flight-limit}
 * @param notRead         true when {@code deliveringCount} was over the limit, so the broker was not asked at all and
 *                        {@code consumers} is empty — which is not the same as nothing being in flight
 * @param truncated       true when the reply held more than the limit and the rest was dropped here
 */
public record InFlight(String queueName, long deliveringCount, int limit, boolean notRead, boolean truncated,
        List<InFlightConsumer> consumers)
{

    public int listedCount()
    {
        return consumers.stream().mapToInt(consumer -> consumer.messages().size()).sum();
    }
}
