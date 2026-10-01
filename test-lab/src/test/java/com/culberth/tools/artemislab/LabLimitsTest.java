package com.culberth.tools.artemislab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LabLimitsTest
{

    private final LabLimits limits = LabLimits.defaults();

    @Test
    @DisplayName("A requested count outside the limit is refused, never clamped to a smaller fixture")
    void refusesCountsOutsideTheLimit()
    {
        assertEquals(1, limits.checkCount(1));
        assertEquals(1000, limits.checkCount(1000));
        assertThrows(LabException.class, () -> limits.checkCount(0));
        assertThrows(LabException.class, () -> limits.checkCount(1001));
        assertThrows(LabException.class, () -> limits.checkBodyBytes(-1));
        assertThrows(LabException.class, () -> limits.checkBodyBytes(65537));
    }

    @Test
    @DisplayName("A run may not generate more than its byte budget in total")
    void enforcesTheRunBudget()
    {
        limits.checkRunBytes(0, 52428800L);
        assertThrows(LabException.class, () -> limits.checkRunBytes(52428800L, 1));
        assertThrows(LabException.class, () -> limits.checkRunBytes(0, -1));
    }

    @Test
    @DisplayName("Configuration cannot raise a limit past its hard ceiling")
    void configurationIsBoundedByCeilings()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new LabLimits(100_001, 65536, 2, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LabLimits(1, 65536, 9, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LabLimits(1, 65536, 2, 1, Duration.ofMinutes(3), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LabLimits(1, 65536, 2, 1, Duration.ZERO, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LabLimits(0, 65536, 2, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1));
    }

    @Test
    @DisplayName("The lab refuses to start bound beyond loopback")
    void loopbackOnly()
    {
        new LoopbackOnlyGuard("127.0.0.1");
        new LoopbackOnlyGuard("localhost");
        new LoopbackOnlyGuard("::1");
        assertThrows(IllegalStateException.class, () -> new LoopbackOnlyGuard(""));
        assertThrows(IllegalStateException.class, () -> new LoopbackOnlyGuard("0.0.0.0"));
        assertThrows(IllegalStateException.class, () -> new LoopbackOnlyGuard("192.168.1.10"));
        assertThrows(IllegalStateException.class, () -> new LoopbackOnlyGuard("127.0.0.1.attacker.example"));
    }
}
