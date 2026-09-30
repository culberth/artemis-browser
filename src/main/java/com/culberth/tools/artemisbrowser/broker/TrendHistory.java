package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The readings this session has kept for trends, bounded three ways: at most one reading per {@code spacingMillis}, at
 * most {@code maxReadings} of them (the oldest dropped first), and at most {@code maxQueues} queues followed. Memory is
 * therefore at most {@code maxReadings × maxQueues} small points per session, whatever the broker holds.
 *
 * <p>
 * Nothing is collected in the background: a reading is taken only when a page lists the queues anyway. So the history
 * has gaps wherever no page was open, and says so rather than drawing a line across them. Not thread-safe; its owner
 * {@link RateTracker} synchronizes.
 */
final class TrendHistory
{

    private final long spacingMillis;
    private final int maxReadings;
    private final int maxQueues;

    private final ArrayDeque<Trends.Mark> readings = new ArrayDeque<>();
    private final Map<String, ArrayDeque<TrendPoint>> byQueue = new LinkedHashMap<>();
    private int epoch;
    private int untracked;

    TrendHistory(long spacingMillis, int maxReadings, int maxQueues)
    {
        this.spacingMillis = Math.max(1, spacingMillis);
        this.maxReadings = Math.max(2, maxReadings);
        this.maxQueues = Math.max(1, maxQueues);
    }

    /** Forgets everything — another broker, or a new session's first reading. */
    void reset()
    {
        readings.clear();
        byQueue.clear();
        epoch = 0;
        untracked = 0;
    }

    /**
     * Keeps this reading when it is at least {@code spacingMillis} after the last one kept, or when the broker
     * restarted in between — a restart is always worth marking.
     *
     * @param restarted   the broker's uptime says it restarted since the last reading
     * @param uptimeKnown false when the uptime could not be read, so a restart could only show as counters going back
     * @return true when the reading was kept
     */
    boolean record(long now, boolean restarted, boolean uptimeKnown, List<QueueOverview> queues)
    {
        Trends.Mark last = readings.peekLast();
        if (!restarted && last != null && now - last.takenAt() < spacingMillis)
        {
            return false;
        }
        if (restarted && last != null)
        {
            epoch++;
        }
        readings.addLast(new Trends.Mark(now, epoch, restarted, uptimeKnown));
        untracked = 0;
        for (QueueOverview queue : queues)
        {
            ArrayDeque<TrendPoint> series = byQueue.get(queue.name());
            if (series == null)
            {
                if (byQueue.size() >= maxQueues)
                {
                    untracked++;
                    continue;
                }
                series = new ArrayDeque<>();
                byQueue.put(queue.name(), series);
            }
            series.addLast(TrendPoint.of(now, epoch, queue));
        }
        while (readings.size() > maxReadings)
        {
            readings.removeFirst();
        }
        long oldest = readings.peekFirst().takenAt();
        for (ArrayDeque<TrendPoint> series : byQueue.values())
        {
            while (!series.isEmpty() && series.peekFirst().takenAt() < oldest)
            {
                series.removeFirst();
            }
        }
        // A queue gone from every kept reading frees its place for another.
        byQueue.values().removeIf(ArrayDeque::isEmpty);
        return true;
    }

    Trends snapshot()
    {
        Map<String, List<TrendPoint>> copy = new LinkedHashMap<>();
        byQueue.forEach((name, series) -> copy.put(name, List.copyOf(series)));
        return new Trends(new ArrayList<>(readings), copy, spacingMillis, maxReadings, maxQueues, untracked);
    }
}
