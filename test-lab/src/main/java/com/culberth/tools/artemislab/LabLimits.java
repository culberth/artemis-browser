package com.culberth.tools.artemislab;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-side bounds on everything the lab generates. The form offers presets inside these; the server enforces them
 * whatever the request says, and the configured values themselves may not exceed the hard ceilings below — a typo in
 * configuration must not become a host-filling workload.
 *
 * @param maxMessages       messages one action may send
 * @param maxBodyBytes      size of one generated body
 * @param maxWorkers        jobs running at once, across the lab
 * @param maxRunBytes       generated body bytes over a whole run
 * @param operationTimeout  one management call, send or receive
 * @param readinessDeadline how long a fixture may take to reach its asserted state
 * @param maxRuns           run manifests kept on disk
 */
@ConfigurationProperties("lab.limits")
public record LabLimits(@DefaultValue("1000") int maxMessages, @DefaultValue("65536") int maxBodyBytes,
        @DefaultValue("2") int maxWorkers, @DefaultValue("52428800") long maxRunBytes,
        @DefaultValue("10s") Duration operationTimeout, @DefaultValue("30s") Duration readinessDeadline,
        @DefaultValue("50") int maxRuns)
{

    static final int CEILING_MESSAGES = 100_000;
    static final int CEILING_BODY_BYTES = 1024 * 1024;
    static final int CEILING_WORKERS = 8;
    static final long CEILING_RUN_BYTES = 512L * 1024 * 1024;
    static final Duration CEILING_OPERATION_TIMEOUT = Duration.ofMinutes(2);
    static final Duration CEILING_READINESS = Duration.ofMinutes(10);
    static final int CEILING_RUNS = 500;

    public LabLimits
    {
        within("lab.limits.max-messages", maxMessages, CEILING_MESSAGES);
        within("lab.limits.max-body-bytes", maxBodyBytes, CEILING_BODY_BYTES);
        within("lab.limits.max-workers", maxWorkers, CEILING_WORKERS);
        within("lab.limits.max-run-bytes", maxRunBytes, CEILING_RUN_BYTES);
        within("lab.limits.max-runs", maxRuns, CEILING_RUNS);
        within("lab.limits.operation-timeout", operationTimeout, CEILING_OPERATION_TIMEOUT);
        within("lab.limits.readiness-deadline", readinessDeadline, CEILING_READINESS);
    }

    /** The defaults, for tests and anything constructed outside Spring. */
    public static LabLimits defaults()
    {
        return new LabLimits(1000, 65536, 2, 52428800L, Duration.ofSeconds(10), Duration.ofSeconds(30), 50);
    }

    /** A requested message count, refused rather than clamped: a silently smaller fixture is a wrong fixture. */
    public int checkCount(int requested)
    {
        if (requested < 1 || requested > maxMessages)
        {
            throw new LabException("Message count must be between 1 and " + maxMessages + "; got " + requested + ".");
        }
        return requested;
    }

    public int checkBodyBytes(int requested)
    {
        if (requested < 0 || requested > maxBodyBytes)
        {
            throw new LabException(
                    "Body size must be between 0 and " + maxBodyBytes + " bytes; got " + requested + ".");
        }
        return requested;
    }

    /** Refuses an action that would take the run past its generated-bytes budget. */
    public void checkRunBytes(long alreadyGenerated, long adding)
    {
        if (adding < 0 || alreadyGenerated + adding > maxRunBytes)
        {
            throw new LabException("That would generate " + (alreadyGenerated + adding)
                    + " bytes in this run; the limit is " + maxRunBytes + ". Clean up and start a new run.");
        }
    }

    private static void within(String name, long value, long ceiling)
    {
        if (value < 1 || value > ceiling)
        {
            throw new IllegalArgumentException(name + " must be between 1 and " + ceiling + "; configured " + value);
        }
    }

    private static void within(String name, Duration value, Duration ceiling)
    {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(ceiling) > 0)
        {
            throw new IllegalArgumentException(
                    name + " must be positive and at most " + ceiling + "; configured " + value);
        }
    }
}
