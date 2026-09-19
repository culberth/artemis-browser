package com.culberth.tools.artemisbrowser.broker;

/**
 * A snapshot of how the broker itself is doing.
 *
 * @param diskUsedPercent   percentage of the disk store in use
 * @param maxDiskPercent    the threshold at which the broker starts blocking producers
 * @param memoryUsedBytes   global address memory in use
 * @param memoryUsedPercent that memory as a percentage of the configured limit
 */
public record BrokerHealth(String version, String uptime, String state, String nodeId, long connectionCount,
        long sessionCount, long consumerCount, long memoryUsedBytes, long memoryUsedPercent, double diskUsedPercent,
        long maxDiskPercent)
{

    /**
     * True when the broker is close enough to its disk threshold to be worth noticing. Crossing {@code maxDiskPercent}
     * is not a warning condition — it is the point at which Artemis blocks producers, so the interesting moment is
     * before that.
     */
    public boolean diskPressure()
    {
        return maxDiskPercent > 0 && diskUsedPercent >= maxDiskPercent * 0.8;
    }

    public boolean memoryPressure()
    {
        return memoryUsedPercent >= 80;
    }

    public boolean running()
    {
        return "STARTED".equalsIgnoreCase(state);
    }
}
