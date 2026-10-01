package com.culberth.tools.artemislab.worker;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A client that outlives the job that started it — a consumer holding deliveries, an idle identified connection, a
 * stream of traffic — owned by one run. Each has its own connection, verified like every other, and its JMS session is
 * only ever used under the worker's own lock: by the job acting on it, or by its own thread, never both.
 *
 * <p>
 * Stopping a worker closes its connection: anything it held unacknowledged goes back to its queue.
 */
public abstract class Worker
{

    public enum State
    {
        ACTIVE, STOPPED, FAILED;
    }

    private final String id;
    private final String runId;
    private final String kind;
    private final String description;
    private final Instant startedAt = Instant.now();

    protected final AtomicLong sent = new AtomicLong();
    protected final AtomicLong received = new AtomicLong();
    protected final AtomicLong acknowledged = new AtomicLong();

    private volatile State state = State.ACTIVE;
    private volatile String detail = "";
    private volatile Instant stoppedAt;

    protected Worker(String id, String runId, String kind, String description)
    {
        this.id = id;
        this.runId = runId;
        this.kind = kind;
        this.description = description;
    }

    public String id()
    {
        return id;
    }

    public String runId()
    {
        return runId;
    }

    public String kind()
    {
        return kind;
    }

    public String description()
    {
        return description;
    }

    public Instant startedAt()
    {
        return startedAt;
    }

    public State state()
    {
        return state;
    }

    public boolean active()
    {
        return state == State.ACTIVE;
    }

    public String detail()
    {
        return detail;
    }

    public Instant stoppedAt()
    {
        return stoppedAt;
    }

    public long sent()
    {
        return sent.get();
    }

    public long received()
    {
        return received.get();
    }

    public long acknowledged()
    {
        return acknowledged.get();
    }

    /** Messages received and not acknowledged — what the broker counts as delivering to this worker. */
    public long held()
    {
        return 0;
    }

    /** The JMS client id it connected with, or blank for an anonymous client. */
    public abstract String clientId();

    /** Closes everything; idempotent. Returns what happened, for the run history. */
    public final synchronized String stop(String why)
    {
        if (state != State.ACTIVE)
        {
            return description + " was already " + state.name().toLowerCase() + ".";
        }
        String effect = close();
        finish(State.STOPPED, why);
        return description + " stopped (" + why + "): " + effect;
    }

    /** Releases the connection. Subclasses describe the effect, e.g. "10 held messages returned to the queue". */
    protected abstract String close();

    protected final void finish(State outcome, String text)
    {
        if (state == State.ACTIVE)
        {
            state = outcome;
            detail = text == null ? "" : text;
            stoppedAt = Instant.now();
        }
    }

    protected final void detail(String text)
    {
        detail = text;
    }

    protected static String cause(Throwable e)
    {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
