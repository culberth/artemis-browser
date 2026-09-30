package com.culberth.tools.artemisbrowser.broker;

import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;

/**
 * Rates for the queues a page has just listed, from this session's previous reading.
 *
 * <p>
 * Pages call {@link #observe}, which costs one attribute read — the broker's uptime — on top of the listing they made
 * anyway. Diagnose calls {@link #forDiagnosis}, which will spend a few seconds taking a second reading when the session
 * has none recent enough, because two of its findings cannot be told apart from one snapshot.
 */
@Service
public class RateService
{

    /** A previous reading older than this is too stale for diagnose to describe as "now". */
    static final long RECENT_MILLIS = 5 * 60_000L;

    /** How long diagnose waits between its own two readings when it has to take them. */
    static final long DIAGNOSE_SAMPLE_MILLIS = 3_000;

    private final BrokerSession brokerSession;
    private final RateTracker tracker;
    private final QueueDirectory queueDirectory;

    public RateService(BrokerSession brokerSession, RateTracker tracker, QueueDirectory queueDirectory)
    {
        this.brokerSession = brokerSession;
        this.tracker = tracker;
        this.queueDirectory = queueDirectory;
    }

    /** What this session has kept for trends, as of the latest reading. */
    public Trends trends()
    {
        return tracker.trends();
    }

    public Rates observe(List<QueueOverview> queues)
    {
        return tracker.observe(broker(), uptimeMillis(), queues, System.currentTimeMillis());
    }

    /**
     * Rates recent enough for diagnose: against the session's last reading when that is at most five minutes old and
     * yields a rate, otherwise from a second reading taken {@link #DIAGNOSE_SAMPLE_MILLIS} after this one.
     */
    public Diagnosis.Measured forDiagnosis(List<QueueOverview> queues)
    {
        long age = tracker.ageOfLastReading(broker(), System.currentTimeMillis());
        Rates first = observe(queues);
        if (first.measured() && age >= 0 && age <= RECENT_MILLIS)
        {
            return new Diagnosis.Measured(first, false);
        }
        try
        {
            Thread.sleep(DIAGNOSE_SAMPLE_MILLIS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new Diagnosis.Measured(Rates.none("interrupted while measuring"), true);
        }
        return new Diagnosis.Measured(observe(queueDirectory.overview()), true);
    }

    private String broker()
    {
        ConnectionInfo info = brokerSession.info();
        return info == null ? "" : info.host() + ":" + info.port();
    }

    /**
     * The broker's uptime, or -1 when it could not be read. Before Phase 13 P3 an unreadable uptime became
     * {@code Long.MAX_VALUE}, which reads as "certainly not restarted"; -1 says "could not check" instead.
     */
    private long uptimeMillis()
    {
        Reading<Object> uptime = Reading
                .attempt(() -> brokerSession.requireManagement().attribute(ResourceNames.BROKER, "uptimeMillis"));
        return uptime.available() && uptime.value() instanceof Number number ? number.longValue() : -1;
    }
}
