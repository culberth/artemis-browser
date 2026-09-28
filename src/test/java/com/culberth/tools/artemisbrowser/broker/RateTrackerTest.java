package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A rate is two readings apart, and three things make one wrong without looking wrong: a broker restart (whose counters
 * restart at what the journal reloads, not at zero), a counter reset, and a reading so close to the last that noise
 * dominates.
 */
class RateTrackerTest
{

    private static final String BROKER = "localhost:61616";
    private static final long HOUR = 3_600_000L;

    @Test
    @DisplayName("the first reading has nothing to compare with, and says so")
    void firstReadingHasNoRate()
    {
        Rates rates = new RateTracker().observe(BROKER, HOUR, List.of(queue("orders", 10, 5)), 0);

        assertFalse(rates.measured());
        assertTrue(rates.unavailable().contains("no earlier reading"));
    }

    @Test
    @DisplayName("the second reading gives messages in and acknowledged per second over the interval")
    void secondReadingGivesARate()
    {
        RateTracker tracker = new RateTracker();
        tracker.observe(BROKER, HOUR, List.of(queue("orders", 100, 40)), 0);

        Rates rates = tracker.observe(BROKER, HOUR + 10_000, List.of(queue("orders", 150, 60)), 10_000);

        assertTrue(rates.measured());
        assertEquals(10_000, rates.intervalMillis());
        assertEquals(5.0, rates.of("orders").inPerSecond());
        assertEquals(2.0, rates.of("orders").ackedPerSecond());
        assertEquals("10s", rates.intervalText());
    }

    @Test
    @DisplayName("a restart between readings is caught from the uptime, even when the counters look plausible")
    void restartIsCaughtFromUptime()
    {
        // Measured on 2.44.0: a queue holding 3 reads added=3 on both sides of a restart.
        RateTracker tracker = new RateTracker();
        tracker.observe(BROKER, HOUR, List.of(queue("orders", 3, 0)), 0);

        Rates rates = tracker.observe(BROKER, 4_000, List.of(queue("orders", 13, 0)), 10_000);

        assertFalse(rates.measured());
        assertTrue(rates.unavailable().contains("restarted"));
    }

    @Test
    @DisplayName("a queue whose counter went back, or which is new, has no rate; the others still do")
    void counterResetDropsOnlyThatQueue()
    {
        RateTracker tracker = new RateTracker();
        tracker.observe(BROKER, HOUR, List.of(queue("reset", 100, 90), queue("steady", 10, 10)), 0);

        Rates rates = tracker.observe(BROKER, HOUR + 5_000,
                List.of(queue("reset", 2, 0), queue("steady", 20, 15), queue("new", 5, 0)), 5_000);

        assertTrue(rates.measured());
        assertNull(rates.of("reset"));
        assertNull(rates.of("new"));
        assertEquals(2.0, rates.of("steady").inPerSecond());
    }

    @Test
    @DisplayName("readings under two seconds apart keep the last rates and the older baseline")
    void tooShortAnIntervalKeepsTheLastRates()
    {
        RateTracker tracker = new RateTracker();
        tracker.observe(BROKER, HOUR, List.of(queue("orders", 0, 0)), 0);
        Rates measured = tracker.observe(BROKER, HOUR + 4_000, List.of(queue("orders", 40, 0)), 4_000);

        Rates quick = tracker.observe(BROKER, HOUR + 4_500, List.of(queue("orders", 41, 0)), 4_500);
        Rates later = tracker.observe(BROKER, HOUR + 8_000, List.of(queue("orders", 80, 0)), 8_000);

        assertEquals(measured, quick);
        assertEquals(4_000, later.intervalMillis(), "measured from the 4s reading, not the 4.5s one");
        assertEquals(10.0, later.of("orders").inPerSecond());
    }

    @Test
    @DisplayName("connecting to another broker starts over")
    void anotherBrokerStartsOver()
    {
        RateTracker tracker = new RateTracker();
        tracker.observe(BROKER, HOUR, List.of(queue("orders", 0, 0)), 0);

        assertFalse(tracker.observe("prod:61616", HOUR, List.of(queue("orders", 50, 0)), 5_000).measured());
        assertEquals(-1, new RateTracker().ageOfLastReading(BROKER, 0));
        assertEquals(5_000 - 5_000, tracker.ageOfLastReading("prod:61616", 5_000));
    }

    @Test
    @DisplayName("rates read as rates: zero, a floor for very slow, one decimal, then whole numbers")
    void formatsRates()
    {
        assertEquals("0", QueueRate.text(0));
        assertEquals("<0.1", QueueRate.text(0.03));
        assertEquals("2.4", QueueRate.text(2.44));
        assertEquals("130", QueueRate.text(129.6));
    }

    private static QueueOverview queue(String name, long added, long acked)
    {
        return new QueueOverview(name, name, "ANYCAST", 0, 0, 0, 1, added, acked, true, false, false);
    }
}
