package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BrokerHealthTest
{

    @Test
    @DisplayName("disk pressure warns before the blocking threshold, not at it")
    void warnsApproachingDiskThreshold()
    {
        // Reaching maxDiskUsage is the point where Artemis blocks producers. A warning that only
        // appears then has told you nothing you would not already have noticed.
        assertFalse(health(70d, 90).diskPressure());
        assertTrue(health(72d, 90).diskPressure());
        assertTrue(health(95d, 90).diskPressure());
    }

    @Test
    @DisplayName("no disk threshold configured means no false alarm")
    void noThresholdNoPressure()
    {
        assertFalse(health(99d, 0).diskPressure());
    }

    @Test
    @DisplayName("memory pressure trips at 80 percent")
    void memoryPressure()
    {
        assertFalse(BrokerHealth.of("2.44.0", "1h", "STARTED", "n", 0, 0, 0, 0, 79, 0d, 90).memoryPressure());
        assertTrue(BrokerHealth.of("2.44.0", "1h", "STARTED", "n", 0, 0, 0, 0, 80, 0d, 90).memoryPressure());
    }

    @Test
    @DisplayName("running is judged on the reported state, case-insensitively")
    void runningState()
    {
        assertTrue(BrokerHealth.of("2.44.0", "1h", "STARTED", "n", 0, 0, 0, 0, 0, 0d, 90).running());
        assertTrue(BrokerHealth.of("2.44.0", "1h", "started", "n", 0, 0, 0, 0, 0, 0d, 90).running());
        assertFalse(BrokerHealth.of("2.44.0", "1h", "STOPPED", "n", 0, 0, 0, 0, 0, 0d, 90).running());
        assertFalse(BrokerHealth.of("2.44.0", "1h", "UNKNOWN", "n", 0, 0, 0, 0, 0, 0d, 90).running());
    }

    @Test
    @DisplayName("a missing disk figure is no pressure, and is named as a check that could not be made")
    void missingDiskIsUncheckedNotHealthy()
    {
        BrokerHealth full = health(95d, 90);
        BrokerHealth unread = new BrokerHealth(full.version(), full.uptime(), full.state(), full.nodeId(),
                full.connectionCount(), full.sessionCount(), full.consumerCount(), full.memoryUsedBytes(),
                Reading.missing(Availability.DENIED, "mops.broker.getAddressMemoryUsagePercentage"),
                Reading.missing(Availability.UNAVAILABLE, ""), full.maxDiskPercent(), full.collectedAt());

        assertFalse(unread.diskPressure());
        assertFalse(unread.memoryPressure());
        assertNull(unread.diskUsedText());
        assertEquals(2, unread.unchecked().size(), unread.unchecked().toString());
        assertTrue(unread.unchecked().get(1).contains("not permitted"), unread.unchecked().toString());
        assertTrue(full.unchecked().isEmpty());
    }

    private BrokerHealth health(double diskUsed, long maxDisk)
    {
        return BrokerHealth.of("2.44.0", "1h", "STARTED", "node", 1, 1, 1, 100, 10, diskUsed, maxDisk);
    }
}
