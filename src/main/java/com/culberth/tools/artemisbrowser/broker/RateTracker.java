package com.culberth.tools.artemisbrowser.broker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * The previous reading of every queue's counters, kept per HTTP session, so the next page load can say how fast each
 * queue is moving.
 *
 * <p>
 * Held in the session rather than read twice per request: the overview's auto-refresh then yields a rate on every
 * refresh after the first at no cost, and moving between pages measures the time in between. Nothing is stored beyond
 * the session. Alongside the one interval that answers "is it moving now", it keeps a bounded {@link TrendHistory} for
 * "how has it changed" — see {@link #trends()}.
 *
 * <p>
 * A restart has to be caught from the broker's uptime, not the counters. Verified on 2.44.0: after a restart
 * {@code messagesAdded} restarts at what the journal reloads, so a queue holding 3 read added=3 on both sides of it. A
 * counter that goes <em>down</em> — a reset — or a queue id that changed — deleted and made again — drops only that
 * queue's rate. An uptime that could not be read is passed as a negative number: then a restart can only show as
 * counters going back, and the trends say so.
 */
@Component
@SessionScope
public class RateTracker
{

    /** Readings closer together than this are too short an interval to be worth a rate; the last rates stand. */
    static final long MIN_INTERVAL_MILLIS = 2_000;

    private Reading last;
    private Rates lastRates = Rates.none("no earlier reading in this session yet");
    private final TrendHistory history;

    /** The defaults, as {@code application.properties} ships them — for tests and code that builds one directly. */
    public RateTracker()
    {
        this(15, 240, 500);
    }

    @Autowired
    public RateTracker(@Value("${artemis.trends.spacing-seconds:15}") long spacingSeconds,
            @Value("${artemis.trends.max-readings:240}") int maxReadings,
            @Value("${artemis.trends.max-queues:500}") int maxQueues)
    {
        this.history = new TrendHistory(spacingSeconds * 1000, maxReadings, maxQueues);
    }

    /**
     * Records this reading and returns the rates since the previous one.
     *
     * @param broker       which broker the counters came from; a different one starts over
     * @param uptimeMillis the broker's uptime now, to tell a restart in between
     */
    public synchronized Rates observe(String broker, long uptimeMillis, List<QueueOverview> queues, long now)
    {
        Reading current = Reading.of(broker, now, uptimeMillis, queues);
        boolean uptimeKnown = uptimeMillis >= 0;
        if (last == null || !last.broker().equals(broker))
        {
            last = current;
            lastRates = Rates.none("no earlier reading in this session yet");
            history.reset();
            history.record(now, false, uptimeKnown, queues);
            return lastRates;
        }
        long interval = now - last.takenAt();
        if (uptimeKnown && uptimeMillis < interval)
        {
            last = current;
            lastRates = Rates.none("the broker restarted since the last reading");
            history.record(now, true, true, queues);
            return lastRates;
        }
        if (interval < MIN_INTERVAL_MILLIS)
        {
            return lastRates;
        }
        history.record(now, false, uptimeKnown, queues);

        Map<String, QueueRate> rates = new HashMap<>();
        for (Map.Entry<String, long[]> entry : current.counters().entrySet())
        {
            long[] before = last.counters().get(entry.getKey());
            long[] after = entry.getValue();
            if (before == null || after[0] < before[0] || after[1] < before[1]
                    || (before[2] >= 0 && after[2] >= 0 && before[2] != after[2]))
            {
                continue;
            }
            double seconds = interval / 1000.0;
            rates.put(entry.getKey(),
                    new QueueRate((after[0] - before[0]) / seconds, (after[1] - before[1]) / seconds));
        }
        last = current;
        lastRates = new Rates(Map.copyOf(rates), interval, null);
        return lastRates;
    }

    /** What this session has kept for trends, as of its latest reading. */
    public synchronized Trends trends()
    {
        return history.snapshot();
    }

    /** How long ago this session last read the broker's counters, or -1 when it has not, or read another broker. */
    public synchronized long ageOfLastReading(String broker, long now)
    {
        return last == null || !last.broker().equals(broker) ? -1 : now - last.takenAt();
    }

    private record Reading(String broker, long takenAt, long uptimeMillis, Map<String, long[]> counters)
    {

        static Reading of(String broker, long now, long uptimeMillis, List<QueueOverview> queues)
        {
            Map<String, long[]> counters = new HashMap<>();
            for (QueueOverview queue : queues)
            {
                counters.put(queue.name(), new long[]
                { queue.messagesAdded(), queue.messagesAcked(), queue.id()
                });
            }
            return new Reading(broker, now, uptimeMillis, counters);
        }
    }
}
