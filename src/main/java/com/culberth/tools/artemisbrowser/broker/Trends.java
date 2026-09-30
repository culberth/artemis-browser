package com.culberth.tools.artemisbrowser.broker;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * What this session has kept for trends: when each reading was taken, and each followed queue's counters at them.
 *
 * @param readings        every reading kept, oldest first
 * @param byQueue         per queue, its points at those readings — absent from a reading where the queue was not
 *                        listed, which is never the same as a zero
 * @param untrackedQueues queues past the follow limit at the latest reading, which have no trend
 */
public record Trends(List<Mark> readings, Map<String, List<TrendPoint>> byQueue, long spacingMillis, int maxReadings,
        int maxQueues, int untrackedQueues)
{

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * One reading kept.
     *
     * @param restarted   the broker's uptime showed a restart since the previous reading
     * @param uptimeKnown false when the uptime could not be read, so a restart could not be checked that time
     */
    public record Mark(long takenAt, int epoch, boolean restarted, boolean uptimeKnown)
    {
    }

    public static Trends none()
    {
        return new Trends(List.of(), Map.of(), 1, 0, 0, 0);
    }

    public boolean empty()
    {
        return readings.isEmpty();
    }

    /** The queue's trend, or null when this session has not followed it. */
    public QueueTrend of(String queueName)
    {
        List<TrendPoint> points = byQueue.get(queueName);
        return points == null || points.isEmpty() ? null : new QueueTrend(points, this);
    }

    /** When the first kept reading was taken — nothing here is older than this. */
    public String sinceText()
    {
        return empty() ? null : TIME.format(java.time.Instant.ofEpochMilli(readings.get(0).takenAt()));
    }

    public long firstAt()
    {
        return empty() ? 0 : readings.get(0).takenAt();
    }

    public long lastAt()
    {
        return empty() ? 0 : readings.get(readings.size() - 1).takenAt();
    }

    /** "15s": the least time between two readings kept. */
    public String spacingText()
    {
        return AddressDetail.ageText(spacingMillis);
    }

    /** "1h 0m": how far back the history can reach when pages are open throughout. */
    public String reachText()
    {
        return AddressDetail.ageText(spacingMillis * maxReadings);
    }

    /** True when some reading could not check the broker's uptime, so a restart may show only as a reset. */
    public boolean restartsUnchecked()
    {
        return readings.stream().anyMatch(mark -> !mark.uptimeKnown());
    }

    /** Readings kept between two times, exclusive — for counting the ones a queue was missing from. */
    int readingsBetween(long from, long to)
    {
        return (int) readings.stream().filter(mark -> mark.takenAt() > from && mark.takenAt() < to).count();
    }

    static String time(long millis)
    {
        return TIME.format(java.time.Instant.ofEpochMilli(millis));
    }
}
