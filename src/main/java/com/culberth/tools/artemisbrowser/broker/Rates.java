package com.culberth.tools.artemisbrowser.broker;

import java.util.Map;

/**
 * Per-queue rates between two readings of the queue counters, or why there are none.
 *
 * @param intervalMillis how far apart the two readings were
 * @param unavailable    why no rates could be given — no earlier reading, the broker restarted in between — or null
 *                       when they were
 */
public record Rates(Map<String, QueueRate> byQueue, long intervalMillis, String unavailable)
{

    public static Rates none(String why)
    {
        return new Rates(Map.of(), 0, why);
    }

    public boolean measured()
    {
        return unavailable == null;
    }

    /** The queue's rate, or null when it has none — new since the last reading, or its counters went back. */
    public QueueRate of(String queueName)
    {
        return byQueue.get(queueName);
    }

    public String intervalText()
    {
        return AddressDetail.ageText(intervalMillis);
    }
}
