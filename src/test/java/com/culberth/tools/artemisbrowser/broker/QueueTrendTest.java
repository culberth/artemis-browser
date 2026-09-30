package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.QueueTrend.Interval;
import com.culberth.tools.artemisbrowser.broker.QueueTrend.Kind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The trend is only as honest as its breaks: readings either side of a restart, a recreated queue or a counter reset
 * are different things, and a stretch nobody watched is not a measurement. Driven through {@link RateTracker} with a
 * made-up clock and uptime, 15s spacing.
 */
class QueueTrendTest
{

    private static final String BROKER = "localhost:61616";
    private static final long T0 = 1_790_000_000_000L;
    private static final long S = 15_000;

    private final RateTracker tracker = new RateTracker();

    @Test
    @DisplayName("one reading is not a trend; two are, with rates and the backlog's change")
    void twoReadingsMakeAnInterval()
    {
        read(0, queue("orders", 10, 100, 90));
        assertTrue(tracker.trends().of("orders").intervals().isEmpty());
        assertFalse(tracker.trends().of("orders").comparable());

        read(S, queue("orders", 25, 130, 105));

        QueueTrend trend = tracker.trends().of("orders");
        Interval interval = only(trend.intervals());
        assertEquals(Kind.MEASURED, interval.kind());
        assertEquals(2.0, interval.inPerSecond(), 1e-9);
        assertEquals(1.0, interval.ackedPerSecond(), 1e-9);
        assertEquals("+15", trend.depthChangeText());
        assertTrue(trend.backlog().startsWith("The backlog grew from 10 to 25"), trend.backlog());
    }

    @Test
    @DisplayName("readings closer than the spacing are not kept, however often the page refreshes")
    void keepsOneReadingPerSpacing()
    {
        read(0, queue("orders", 1, 1, 0));
        read(5_000, queue("orders", 2, 2, 0));
        read(10_000, queue("orders", 3, 3, 0));
        read(S, queue("orders", 4, 4, 0));

        assertEquals(2, tracker.trends().readings().size());
    }

    @Test
    @DisplayName("a restart breaks the line: no rate across it, and it says so")
    void restartIsABreak()
    {
        read(0, queue("orders", 5, 100, 95));
        read(S, 60_000_000, queue("orders", 7, 120, 113));
        read(2 * S, 4_000, queue("orders", 7, 7, 0));

        QueueTrend trend = tracker.trends().of("orders");
        assertEquals(Kind.RESTARTED, trend.intervals().get(1).kind());
        assertEquals(2, trend.polylines(100, 20).size(), "two stretches, not one line across the restart");
        assertTrue(trend.lastBreak().contains("the broker restarted"), trend.lastBreak());
        assertFalse(trend.comparable(), "nothing after the restart to compare yet");
    }

    @Test
    @DisplayName("a queue made again under the same name is a different queue: a break, and no rate")
    void recreatedQueueIsABreak()
    {
        read(0, queue("orders", 5, 50, 45).withId(931));
        Rates rates = read(S, queue("orders", 6, 60, 54).withId(940));

        assertEquals(Kind.RECREATED, only(tracker.trends().of("orders").intervals()).kind());
        assertNull(rates.of("orders"), "the rate would compare two different queues");
    }

    @Test
    @DisplayName("counters that went back are a reset: a break, not a negative rate")
    void counterResetIsABreak()
    {
        read(0, queue("orders", 5, 50, 45));
        read(S, queue("orders", 5, 3, 0));

        assertEquals(Kind.RESET, only(tracker.trends().of("orders").intervals()).kind());
    }

    @Test
    @DisplayName("a long wait between readings is a gap, and a reading the queue was missing from is counted")
    void gapsAndAbsences()
    {
        read(0, queue("orders", 1, 1, 0), queue("other", 0, 0, 0));
        read(S, queue("other", 0, 0, 0));
        read(10 * 60_000, queue("orders", 1, 1, 0), queue("other", 0, 0, 0));

        Interval interval = only(tracker.trends().of("orders").intervals());
        assertTrue(interval.gap());
        assertEquals(1, interval.missed(), "absent from one reading, which is not the same as empty");
    }

    @Test
    @DisplayName("retention drops the oldest readings and the points that went with them")
    void retentionIsBounded()
    {
        RateTracker small = new RateTracker(15, 3, 500);
        for (int i = 0; i < 6; i++)
        {
            small.observe(BROKER, 3_600_000L + i * S, List.of(queue("orders", i, i, 0)), T0 + i * S);
        }

        Trends trends = small.trends();
        assertEquals(3, trends.readings().size());
        assertEquals(3, trends.of("orders").points().size());
        assertEquals(T0 + 3 * S, trends.firstAt());
    }

    @Test
    @DisplayName("past the queue limit a queue has no trend, and the count of those is kept")
    void queueLimitIsBounded()
    {
        RateTracker small = new RateTracker(15, 240, 1);
        small.observe(BROKER, 3_600_000L, List.of(queue("a", 0, 0, 0), queue("b", 0, 0, 0)), T0);

        assertNull(small.trends().of("b"));
        assertEquals(1, small.trends().untrackedQueues());
    }

    @Test
    @DisplayName("says when acknowledgments stopped, and when nothing was acknowledged at all")
    void consumptionChanges()
    {
        read(0, queue("orders", 0, 10, 10));
        read(S, queue("orders", 0, 20, 20));
        read(2 * S, queue("orders", 10, 30, 20));

        assertTrue(tracker.trends().of("orders").consumption().startsWith("Acknowledgments stopped between"),
                tracker.trends().of("orders").consumption());

        RateTracker idle = new RateTracker();
        idle.observe(BROKER, 3_600_000L, List.of(queue("q", 5, 5, 0)), T0);
        idle.observe(BROKER, 3_600_000L + S, List.of(queue("q", 8, 8, 0)), T0 + S);
        assertTrue(idle.trends().of("q").consumption().startsWith("Nothing was acknowledged"),
                idle.trends().of("q").consumption());
    }

    @Test
    @DisplayName("expired and killed now are reported apart from acknowledgments, and consumer changes are dated")
    void removalsAndConsumers()
    {
        read(0, new QueueOverview("orders", "orders", "ANYCAST", 10, 0, 0, 2, 10, 0, true, false, false, 0, 0));
        read(S, new QueueOverview("orders", "orders", "ANYCAST", 6, 0, 0, 0, 10, 0, true, false, false, 3, 1));

        QueueTrend trend = tracker.trends().of("orders");
        assertTrue(trend.removalsNow().startsWith("3 expired and 1 killed"), trend.removalsNow());
        assertEquals(0.0, only(trend.intervals()).ackedPerSecond(), "expiry is not acknowledgment");
        assertTrue(trend.consumers().startsWith("Consumers went from 2 to 0"), trend.consumers());
    }

    @Test
    @DisplayName("an uptime that could not be read is not taken as 'no restart', and the trends say so")
    void unreadableUptime()
    {
        tracker.observe(BROKER, -1, List.of(queue("orders", 1, 1, 0)), T0);
        tracker.observe(BROKER, -1, List.of(queue("orders", 2, 2, 0)), T0 + S);

        assertTrue(tracker.trends().restartsUnchecked());
        assertEquals(Kind.MEASURED, only(tracker.trends().of("orders").intervals()).kind());
    }

    @Test
    @DisplayName("another broker starts the history over")
    void anotherBrokerStartsOver()
    {
        read(0, queue("orders", 1, 1, 0));
        read(S, queue("orders", 2, 2, 0));
        tracker.observe("elsewhere:61616", 3_600_000L, List.of(queue("orders", 9, 9, 0)), T0 + 2 * S);

        assertEquals(1, tracker.trends().readings().size());
    }

    @Test
    @DisplayName("the sparkline shares the session's time axis and stays inside its box")
    void sparklineFitsItsBox()
    {
        read(0, queue("orders", 0, 0, 0));
        read(S, queue("orders", 10, 10, 0));

        String line = only(tracker.trends().of("orders").polylines(100, 20));
        assertEquals("0.0,19.0 99.0,1.0", line);
    }

    private Rates read(long offset, QueueOverview... queues)
    {
        return read(offset, 3_600_000L + offset, queues);
    }

    private Rates read(long offset, long uptime, QueueOverview... queues)
    {
        return tracker.observe(BROKER, uptime, List.of(queues), T0 + offset);
    }

    private static QueueOverview queue(String name, long depth, long added, long acked)
    {
        return new QueueOverview(name, name, "ANYCAST", depth, 0, 0, 1, added, acked, true, false, false);
    }

    private static <T> T only(List<T> items)
    {
        assertEquals(1, items.size(), String.valueOf(items));
        return items.get(0);
    }
}
