package com.culberth.tools.artemislab.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobRunnerTest
{

    private final JobRunner runner = new JobRunner(LabLimits.defaults());
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop()
    {
        release.countDown();
        runner.destroy();
    }

    private JobRunner.Work blocking()
    {
        return job ->
        {
            while (!release.await(20, TimeUnit.MILLISECONDS))
            {
                job.checkCancelled();
            }
            return "done";
        };
    }

    @Test
    @DisplayName("A second submission with the same token returns the first job and runs nothing more")
    void duplicateSubmissionIsIdempotent() throws Exception
    {
        AtomicInteger runs = new AtomicInteger();
        Job first = runner.submit("r1", "t1", "send", job -> "sent " + runs.incrementAndGet());
        runner.awaitIdle("r1", Duration.ofSeconds(5));
        Job second = runner.submit("r1", "t1", "send", job -> "sent " + runs.incrementAndGet());

        assertSame(first, second);
        assertEquals(1, runs.get());
        assertEquals(Job.State.SUCCEEDED, first.state());
    }

    @Test
    @DisplayName("One job per run at a time; broker work waits for everything else")
    void conflictingWorkIsRefused()
    {
        runner.submit("r1", "t1", "long", blocking());

        assertThrows(LabException.class, () -> runner.submit("r1", "t2", "again", job -> "x"));
        assertThrows(LabException.class, () -> runner.submit(JobRunner.BROKER_SCOPE, "t3", "stop broker", job -> "x"));
    }

    @Test
    @DisplayName("Past the worker limit a request is refused, not queued")
    void workerLimit()
    {
        runner.submit("r1", "t1", "one", blocking());
        runner.submit("r2", "t2", "two", blocking());

        LabException refused = assertThrows(LabException.class, () -> runner.submit("r3", "t3", "three", job -> "x"));
        assertTrue(refused.getMessage().contains("max-workers"));
    }

    @Test
    @DisplayName("A form without a token is refused")
    void tokenRequired()
    {
        assertThrows(LabException.class, () -> runner.submit("r1", "", "x", job -> "x"));
    }

    @Test
    @DisplayName("Cancelling stops the work and records it as cancelled")
    void cancel() throws Exception
    {
        Job job = runner.submit("r1", "t1", "long", blocking());
        runner.cancelAll("r1");

        assertTrue(runner.awaitIdle("r1", Duration.ofSeconds(5)));
        assertEquals(Job.State.CANCELLED, job.state());
    }

    @Test
    @DisplayName("A job cancelled before its thread starts finishes as cancelled instead of staying 'running'")
    void cancelBeforeStart() throws Exception
    {
        // Cancelling through the executor's Future used to stop such a job from ever running, so it never finished and
        // blocked its run for good. Hit the window repeatedly.
        for (int i = 0; i < 200; i++)
        {
            String scope = "race" + i;
            Job job = runner.submit(scope, "token" + i, "short", j ->
            {
                j.checkCancelled();
                return "ran";
            });
            runner.cancelAll(scope);
            assertTrue(runner.awaitIdle(scope, Duration.ofSeconds(5)), "attempt " + i + " stayed " + job.state());
        }
    }

    @Test
    @DisplayName("A failure is kept with its message")
    void failure() throws Exception
    {
        Job job = runner.submit("r1", "t1", "fails", j ->
        {
            throw new LabException("broker said no");
        });
        runner.awaitIdle("r1", Duration.ofSeconds(5));

        assertEquals(Job.State.FAILED, job.state());
        assertEquals("broker said no", job.detail());
    }
}
