package com.culberth.tools.artemislab.job;

import java.time.Instant;
import java.util.concurrent.CancellationException;

/**
 * One bounded piece of lab work — provisioning, a scenario, a cleanup — and its outcome. Also the context the work
 * checks for cancellation between steps: a worker blocked in a send is interrupted as well, and its send times out
 * under the factory's {@code callTimeout}.
 */
public final class Job
{

    public enum State
    {
        RUNNING, SUCCEEDED, FAILED, CANCELLED;

        public boolean active()
        {
            return this == RUNNING;
        }
    }

    private final String id;
    private final String scope;
    private final String description;
    private final Instant submittedAt = Instant.now();

    private volatile State state = State.RUNNING;
    private volatile String detail = "";
    private volatile String progress = "";
    private volatile Instant finishedAt;
    private volatile boolean cancelRequested;
    /** The worker thread running it, once it has started; interrupted on cancellation. */
    private Thread runner;

    Job(String id, String scope, String description)
    {
        this.id = id;
        this.scope = scope;
        this.description = description;
    }

    public String id()
    {
        return id;
    }

    public String scope()
    {
        return scope;
    }

    public String description()
    {
        return description;
    }

    public Instant submittedAt()
    {
        return submittedAt;
    }

    public State state()
    {
        return state;
    }

    public String detail()
    {
        return detail;
    }

    public String progress()
    {
        return progress;
    }

    public Instant finishedAt()
    {
        return finishedAt;
    }

    public boolean active()
    {
        return state.active();
    }

    /** Throws if cancellation was asked for; work calls it between steps. */
    public void checkCancelled()
    {
        if (cancelRequested || Thread.currentThread().isInterrupted())
        {
            throw new CancellationException("Cancelled on request.");
        }
    }

    public void progress(String text)
    {
        this.progress = text;
    }

    public boolean cancelRequested()
    {
        return cancelRequested;
    }

    /**
     * Called on the worker thread as it starts. False if the job was cancelled before it got a thread, in which case it
     * is finished here and must not run: cancelling through the executor's {@code Future} instead would stop the task
     * from ever running, leaving the job "running" forever and its run blocked.
     */
    synchronized boolean begin()
    {
        if (cancelRequested)
        {
            finish(State.CANCELLED, "Cancelled before it started.");
            return false;
        }
        runner = Thread.currentThread();
        return true;
    }

    /** Called on the worker thread as it ends, so a late cancellation does not interrupt the thread's next job. */
    synchronized void end()
    {
        runner = null;
        Thread.interrupted();
    }

    synchronized void requestCancel()
    {
        cancelRequested = true;
        if (runner != null)
        {
            runner.interrupt();
        }
    }

    synchronized void finish(State outcome, String text)
    {
        if (state.active())
        {
            state = outcome;
            detail = text == null ? "" : text;
            finishedAt = Instant.now();
        }
    }
}
