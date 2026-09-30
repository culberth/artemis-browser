package com.culberth.tools.artemislab.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import com.culberth.tools.artemislab.run.ActionRecord.Outcome;
import com.culberth.tools.artemislab.run.RunManifest.BrokerRef;
import com.culberth.tools.artemislab.run.RunManifest.RunState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStoreTest
{

    @TempDir
    Path dir;

    static RunManifest manifest(String runId, Instant at)
    {
        return new RunManifest(RunManifest.SCHEMA, runId, "rev", at, "UTC", "abc123",
                new BrokerRef("b1", "apache/artemis:2.55.0-alpine", "2.55.0", "node-1", "127.0.0.1:62616"),
                RunState.OPEN, 0, List.of(), List.of(), 0, List.of());
    }

    private static LabLimits limits(int maxRuns)
    {
        return new LabLimits(1000, 65536, 2, 52428800L, Duration.ofSeconds(10), Duration.ofSeconds(30), maxRuns);
    }

    @Test
    @DisplayName("A manifest survives a restart, and carries no credential of any kind")
    void persistsWithoutCredentials() throws Exception
    {
        RunStore store = new RunStore(dir, limits(50));
        store.create(manifest("r1", Instant.now()));
        store.update("r1", r -> r.withResult(
                new CaseResult("C01", CaseResult.Status.PASS, CaseResult.Status.FAIL, "notes", Instant.now())));

        String json = Files.readString(dir.resolve("runs/r1.json"), StandardCharsets.UTF_8);
        assertFalse(json.toLowerCase().contains("password"), json);
        RunManifest reloaded = new RunStore(dir, limits(50)).get("r1");
        assertEquals("C01", reloaded.results().get(0).caseId());
        assertEquals("node-1", reloaded.broker().nodeId());
    }

    @Test
    @DisplayName("After a lab restart an unfinished job is interrupted and an open run's broker is gone; nothing resumes")
    void restartReconciles()
    {
        RunStore store = new RunStore(dir, limits(50));
        store.create(manifest("r1", Instant.now()));
        store.update("r1",
                r -> r.withAction(ActionRecord.now("j1", "LAB-SMOKE", Outcome.STARTED, ""))
                        .withAction(ActionRecord.now("j0", "LAB-SMOKE", Outcome.STARTED, ""))
                        .withAction(ActionRecord.now("j0", "LAB-SMOKE", Outcome.SUCCEEDED, "ok")));

        RunManifest reloaded = new RunStore(dir, limits(50)).get("r1");

        assertEquals(RunState.BROKER_GONE, reloaded.state());
        List<ActionRecord> interrupted = reloaded.history().stream()
                .filter(a -> a.outcome() == Outcome.INTERRUPTED && a.jobId().equals("j1")).toList();
        assertEquals(1, interrupted.size());
        assertTrue(reloaded.history().stream()
                .noneMatch(a -> a.outcome() == Outcome.INTERRUPTED && a.jobId().equals("j0")));
    }

    @Test
    @DisplayName("Past the retention bound the oldest finished run goes; with none finished a new run is refused")
    void bounded()
    {
        RunStore store = new RunStore(dir, limits(2));
        store.create(manifest("r1", Instant.parse("2026-09-30T10:00:00Z")));
        store.create(manifest("r2", Instant.parse("2026-09-30T11:00:00Z")));

        assertThrows(LabException.class, () -> store.create(manifest("r3", Instant.now())));

        store.update("r1", r -> r.withState(RunState.CLEANED));
        store.create(manifest("r3", Instant.now()));
        assertTrue(store.find("r1").isEmpty());
        assertFalse(Files.exists(dir.resolve("runs/r1.json")));
    }

    @Test
    @DisplayName("History is bounded and says how much it dropped")
    void historyBounded()
    {
        RunManifest run = manifest("r1", Instant.now());
        for (int i = 0; i < RunManifest.MAX_HISTORY + 5; i++)
        {
            run = run.withAction(ActionRecord.now("", "a" + i, Outcome.SUCCEEDED, ""));
        }
        assertEquals(RunManifest.MAX_HISTORY, run.history().size());
        assertEquals(5, run.droppedHistory());
        assertEquals("a5", run.history().get(0).action());
    }

    @Test
    @DisplayName("A run id that could escape the directory is refused")
    void runIdIsAName()
    {
        RunStore store = new RunStore(dir, limits(50));
        assertThrows(LabException.class, () -> store.create(manifest("../x", Instant.now())));
    }
}
