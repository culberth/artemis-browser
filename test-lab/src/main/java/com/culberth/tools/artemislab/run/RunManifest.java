package com.culberth.tools.artemislab.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Everything a run did and found, as written to disk and downloaded. No passwords: there is no field for one, and the
 * broker's is never part of any detail string.
 *
 * @param schema          format tag, bumped on an incompatible change
 * @param runId           also the resource prefix: every owned name starts {@code lab.<runId>.}
 * @param catalogRevision the scenario catalog's revision when the run was created
 * @param createdAt       when
 * @param zone            the lab's time zone, so the timestamps can be read against Browser's
 * @param browserCommit   the Artemis Browser revision under test, as entered; may be blank
 * @param broker          which broker the run is bound to, by identity
 * @param state           the run's lifecycle
 * @param generatedBytes  body bytes sent so far, against {@code lab.limits.max-run-bytes}
 * @param resources       exactly what the run owns — the only things cleanup may delete
 * @param history         the action log, oldest first, bounded
 * @param droppedHistory  how many of the oldest entries the bound dropped
 * @param results         per-case results, fixture and Browser kept apart
 * @param sent            the send manifest: one line per message the broker accepted, bounded
 * @param droppedSent     how many sends the bound left out of {@code sent}
 * @param baseline        the latest reading of every owned queue's counters, for the read-only check (E04); null until
 *                        taken
 */
public record RunManifest(String schema, String runId, String catalogRevision, Instant createdAt, String zone,
        String browserCommit, BrokerRef broker, RunState state, long generatedBytes, List<OwnedResource> resources,
        List<ActionRecord> history, int droppedHistory, List<CaseResult> results, List<SentMessage> sent,
        int droppedSent, Reading baseline)
{

    public static final String SCHEMA = "artemis-lab-run/1";
    static final int MAX_HISTORY = 500;
    static final int MAX_SENT = 2000;

    public RunManifest
    {
        resources = resources == null ? List.of() : List.copyOf(resources);
        history = history == null ? List.of() : List.copyOf(history);
        results = results == null ? List.of() : List.copyOf(results);
        sent = sent == null ? List.of() : List.copyOf(sent);
    }

    /** A new, empty, open run. */
    public static RunManifest open(String runId, String catalogRevision, Instant createdAt, String zone,
            String browserCommit, BrokerRef broker)
    {
        return new RunManifest(SCHEMA, runId, catalogRevision, createdAt, zone, browserCommit, broker, RunState.OPEN, 0,
                List.of(), List.of(), 0, List.of(), List.of(), 0, null);
    }

    /** The run's resource prefix. Naming only — never on its own a reason to delete something. */
    public String prefix()
    {
        return "lab." + runId + ".";
    }

    public boolean acceptsScenarios()
    {
        return state == RunState.OPEN;
    }

    public boolean owns(String name)
    {
        return resources.stream().anyMatch(r -> r.name().equals(name) && r.state().mayExist());
    }

    public RunManifest withState(RunState next)
    {
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, next,
                generatedBytes, resources, history, droppedHistory, results, sent, droppedSent, baseline);
    }

    public RunManifest withGeneratedBytes(long bytes)
    {
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state, bytes,
                resources, history, droppedHistory, results, sent, droppedSent, baseline);
    }

    public RunManifest withResource(OwnedResource resource)
    {
        List<OwnedResource> next = new ArrayList<>(resources);
        next.removeIf(r -> r.kind() == resource.kind() && r.name().equals(resource.name()));
        next.add(resource);
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state,
                generatedBytes, next, history, droppedHistory, results, sent, droppedSent, baseline);
    }

    public RunManifest withResources(UnaryOperator<OwnedResource> change)
    {
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state,
                generatedBytes, resources.stream().map(change).toList(), history, droppedHistory, results, sent,
                droppedSent, baseline);
    }

    public RunManifest withAction(ActionRecord action)
    {
        List<ActionRecord> next = new ArrayList<>(history);
        next.add(action);
        int dropped = droppedHistory;
        while (next.size() > MAX_HISTORY)
        {
            next.remove(0);
            dropped++;
        }
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state,
                generatedBytes, resources, next, dropped, results, sent, droppedSent, baseline);
    }

    public RunManifest withResult(CaseResult result)
    {
        List<CaseResult> next = new ArrayList<>(results);
        next.removeIf(r -> r.caseId().equals(result.caseId()));
        next.add(result);
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state,
                generatedBytes, resources, history, droppedHistory, next, sent, droppedSent, baseline);
    }

    /** Appends sends, keeping the earliest {@link #MAX_SENT} and counting the rest, and adds their body bytes. */
    public RunManifest withSent(List<SentMessage> more)
    {
        List<SentMessage> next = new ArrayList<>(sent);
        int dropped = droppedSent;
        for (SentMessage message : more)
        {
            if (next.size() < MAX_SENT)
            {
                next.add(message);
            }
            else
            {
                dropped++;
            }
        }
        long bytes = generatedBytes + more.stream().mapToLong(SentMessage::bodyBytes).sum();
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state, bytes,
                resources, history, droppedHistory, results, next, dropped, baseline);
    }

    public RunManifest withBaseline(Reading reading)
    {
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state,
                generatedBytes, resources, history, droppedHistory, results, sent, droppedSent, reading);
    }

    /**
     * Every owned queue's counters at one moment.
     *
     * @param takenAt when
     * @param queues  queue name to attribute name to value
     */
    public record Reading(Instant takenAt, java.util.Map<String, java.util.Map<String, Long>> queues)
    {
    }

    /**
     * @param brokerId the lab's id for it
     * @param image    the pinned image
     * @param version  what it reported
     * @param nodeId   its identity
     * @param endpoint host:port, no credentials
     * @param profile  how the broker was configured at startup; STANDARD when absent from an older manifest
     */
    public record BrokerRef(String brokerId, String image, String version, String nodeId, String endpoint,
            String profile)
    {

        public BrokerRef
        {
            profile = profile == null || profile.isBlank() ? "STANDARD" : profile;
        }

        public BrokerRef(String brokerId, String image, String version, String nodeId, String endpoint)
        {
            this(brokerId, image, version, nodeId, endpoint, "STANDARD");
        }
    }

    public enum RunState
    {
        /** Accepting scenarios. */
        OPEN,
        /** Cleanup is running. */
        CLEANING,
        /** Every owned resource is gone, and proven gone. */
        CLEANED,
        /** Something could not be removed; blocks reuse until a retry succeeds. */
        CLEANUP_FAILED,
        /** Its broker belonged to an earlier lab process and is no longer the lab's. */
        BROKER_GONE
    }
}
