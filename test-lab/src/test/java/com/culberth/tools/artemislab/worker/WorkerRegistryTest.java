package com.culberth.tools.artemislab.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkerRegistryTest
{

    private static final class Idle extends Worker
    {
        int closed;

        Idle(String runId)
        {
            super(WorkerRegistry.newId(), runId, "idle", "idle client");
        }

        @Override
        public String clientId()
        {
            return "";
        }

        @Override
        protected String close()
        {
            closed++;
            return "disconnected";
        }
    }

    private final WorkerRegistry registry = new WorkerRegistry(new LabLimits(1000, 262144, 2, 52428800L,
            Duration.ofSeconds(10), Duration.ofSeconds(30), 50, 2, 200, Duration.ofMinutes(5)));

    @Test
    @DisplayName("Live workers are bounded across the lab; stopped ones free their place")
    void bounded()
    {
        Idle one = registry.add(new Idle("r1"));
        registry.add(new Idle("r2"));

        LabException refused = assertThrows(LabException.class, () -> registry.reserve(1));
        assertTrue(refused.getMessage().contains("max-live-workers"));

        one.stop("test");
        registry.reserve(1);
    }

    @Test
    @DisplayName("Stopping a run's workers closes each once and leaves other runs alone")
    void stopAllByRun()
    {
        Idle a = registry.add(new Idle("r1"));
        Idle b = registry.add(new Idle("r2"));

        assertEquals(1, registry.stopAll("r1", "cleanup").size());
        assertFalse(a.active());
        assertTrue(b.active());
        assertTrue(a.stop("again").contains("already stopped"));
        assertEquals(1, a.closed);
    }

    @Test
    @DisplayName("A worker is found only within its own run")
    void scopedToRun()
    {
        Idle a = registry.add(new Idle("r1"));
        assertTrue(registry.find("r1", a.id()).isPresent());
        assertTrue(registry.find("r2", a.id()).isEmpty());
    }
}
