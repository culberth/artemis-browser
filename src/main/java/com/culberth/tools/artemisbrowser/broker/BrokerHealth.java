package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A snapshot of how the broker itself is doing, one {@link Reading} per attribute.
 *
 * <p>
 * Each attribute is read on its own, so one the broker will not return leaves the others standing, and each says why it
 * is missing. The pressure checks answer false when their inputs are missing — but only alongside {@link #unchecked()},
 * which names what could not be checked, so "no pressure" and "could not tell" never look the same. Before Phase 13 a
 * value that failed to parse became 0, which reads as a perfectly healthy disk.
 *
 * @param diskUsedPercent   percentage of the disk store in use
 * @param maxDiskPercent    the threshold at which the broker starts blocking producers
 * @param memoryUsedBytes   global address memory in use
 * @param memoryUsedPercent that memory as a percentage of the configured limit, in whole percent — so a broker with a
 *                          large limit reads 0 until it is using a good deal
 * @param globalMaxBytes    that configured limit, {@code global-max-size}, in bytes
 * @param uptimeMillis      how long the broker has been up — what tells a restart between two readings; the
 *                          {@code uptime} text is for people
 */
public record BrokerHealth(Reading<String> version, Reading<String> uptime, Reading<String> state,
        Reading<String> nodeId, Reading<Long> connectionCount, Reading<Long> sessionCount, Reading<Long> consumerCount,
        Reading<Long> memoryUsedBytes, Reading<Long> memoryUsedPercent, Reading<Double> diskUsedPercent,
        Reading<Long> maxDiskPercent, Instant collectedAt, Reading<Long> globalMaxBytes, Reading<Long> uptimeMillis)
{

    /** Without the uptime in milliseconds — as built before an incident snapshot needed it. */
    public BrokerHealth(Reading<String> version, Reading<String> uptime, Reading<String> state, Reading<String> nodeId,
            Reading<Long> connectionCount, Reading<Long> sessionCount, Reading<Long> consumerCount,
            Reading<Long> memoryUsedBytes, Reading<Long> memoryUsedPercent, Reading<Double> diskUsedPercent,
            Reading<Long> maxDiskPercent, Instant collectedAt, Reading<Long> globalMaxBytes)
    {
        this(version, uptime, state, nodeId, connectionCount, sessionCount, consumerCount, memoryUsedBytes,
                memoryUsedPercent, diskUsedPercent, maxDiskPercent, collectedAt, globalMaxBytes,
                Reading.notCollected("not asked for"));
    }

    /** Without the global limit — as built before it was read. */
    public BrokerHealth(Reading<String> version, Reading<String> uptime, Reading<String> state, Reading<String> nodeId,
            Reading<Long> connectionCount, Reading<Long> sessionCount, Reading<Long> consumerCount,
            Reading<Long> memoryUsedBytes, Reading<Long> memoryUsedPercent, Reading<Double> diskUsedPercent,
            Reading<Long> maxDiskPercent, Instant collectedAt)
    {
        this(version, uptime, state, nodeId, connectionCount, sessionCount, consumerCount, memoryUsedBytes,
                memoryUsedPercent, diskUsedPercent, maxDiskPercent, collectedAt, Reading.notCollected("not asked for"));
    }

    /** Every value present — for tests and for the places that build one from known numbers. */
    public static BrokerHealth of(String version, String uptime, String state, String nodeId, long connectionCount,
            long sessionCount, long consumerCount, long memoryUsedBytes, long memoryUsedPercent, double diskUsedPercent,
            long maxDiskPercent)
    {
        return new BrokerHealth(Reading.of(version), Reading.of(uptime), Reading.of(state), Reading.of(nodeId),
                Reading.of(connectionCount), Reading.of(sessionCount), Reading.of(consumerCount),
                Reading.of(memoryUsedBytes), Reading.of(memoryUsedPercent), Reading.of(diskUsedPercent),
                Reading.of(maxDiskPercent), Instant.now(), Reading.of(1L << 30), Reading.of(3_600_000L));
    }

    /**
     * Address memory against {@code global-max-size} as a fraction, from the two byte counts where both were read —
     * finer than the broker's whole percent, which says 0% for anything under 1%. Null when either is missing or there
     * is no limit.
     */
    public Double memoryUsedFraction()
    {
        if (!memoryUsedBytes.available() || !globalMaxBytes.available() || globalMaxBytes.value() <= 0)
        {
            return null;
        }
        return (double) memoryUsedBytes.value() / globalMaxBytes.value();
    }

    /**
     * True when the broker is close enough to its disk threshold to be worth noticing. Crossing {@code maxDiskPercent}
     * is not a warning condition — it is the point at which Artemis blocks producers, so the interesting moment is
     * before that. False when either number is missing; {@link #unchecked()} says so.
     */
    public boolean diskPressure()
    {
        if (!diskUsedPercent.available() || !maxDiskPercent.available())
        {
            return false;
        }
        return maxDiskPercent.value() > 0 && diskUsedPercent.value() >= maxDiskPercent.value() * 0.8;
    }

    public boolean memoryPressure()
    {
        return memoryUsedPercent.available() && memoryUsedPercent.value() >= 80;
    }

    public boolean running()
    {
        return state.available() && "STARTED".equalsIgnoreCase(state.value());
    }

    public boolean stateKnown()
    {
        return state.available();
    }

    /** "12.34%", or null when either input is missing. */
    public String diskUsedText()
    {
        return diskUsedPercent.available() ? String.format(Locale.ROOT, "%.2f%%", diskUsedPercent.value()) : null;
    }

    /**
     * The health checks that could not be made, each with why — for diagnose to say so, rather than let a missing
     * number pass as a healthy one.
     */
    public List<String> unchecked()
    {
        List<String> unchecked = new ArrayList<>();
        if (!diskUsedPercent.available() || !maxDiskPercent.available())
        {
            Reading<?> missing = diskUsedPercent.available() ? maxDiskPercent : diskUsedPercent;
            unchecked.add("Disk use against its limit: " + missing.explained());
        }
        if (!memoryUsedPercent.available())
        {
            unchecked.add("Address memory against its limit: " + memoryUsedPercent.explained());
        }
        if (!state.available())
        {
            unchecked.add("Whether the broker reports itself started: " + state.explained());
        }
        return unchecked;
    }
}
