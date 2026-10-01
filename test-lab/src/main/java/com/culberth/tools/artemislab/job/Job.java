package com.culberth.tools.artemislab.job;

import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;

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
    private volatile Future<?> future;

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

    void attach(Future<?> running)
    {
        this.future = running;
    }

    void requestCancel()
    {
        cancelRequested = true;
        Future<?> running = future;
        if (running != null)
        {
            running.cancel(true);
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
