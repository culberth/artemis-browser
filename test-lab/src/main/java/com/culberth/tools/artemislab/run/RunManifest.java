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
 */
public record RunManifest(String schema, String runId, String catalogRevision, Instant createdAt, String zone,
        String browserCommit, BrokerRef broker, RunState state, long generatedBytes, List<OwnedResource> resources,
        List<ActionRecord> history, int droppedHistory, List<CaseResult> results)
{

    public static final String SCHEMA = "artemis-lab-run/1";
    static final int MAX_HISTORY = 500;

    public RunManifest
    {
        resources = resources == null ? List.of() : List.copyOf(resources);
        history = history == null ? List.of() : List.copyOf(history);
        results = results == null ? List.of() : List.copyOf(results);
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

    public RunManifest withState(RunState next)
    {
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, next,
                generatedBytes, resources, history, droppedHistory, results);
    }

    public RunManifest withGeneratedBytes(long bytes)
    {
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state, bytes,
                resources, history, droppedHistory, results);
    }

    public RunManifest withResource(OwnedResource resource)
    {
        List<OwnedResource> next = new ArrayList<>(resources);
        next.removeIf(r -> r.kind() == resource.kind() && r.name().equals(resource.name()));
        next.add(resource);
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state,
                generatedBytes, next, history, droppedHistory, results);
    }

    public RunManifest withResources(UnaryOperator<OwnedResource> change)
    {
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state,
                generatedBytes, resources.stream().map(change).toList(), history, droppedHistory, results);
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
                generatedBytes, resources, next, dropped, results);
    }

    public RunManifest withResult(CaseResult result)
    {
        List<CaseResult> next = new ArrayList<>(results);
        next.removeIf(r -> r.caseId().equals(result.caseId()));
        next.add(result);
        return new RunManifest(schema, runId, catalogRevision, createdAt, zone, browserCommit, broker, state,
                generatedBytes, resources, history, droppedHistory, next);
    }

    /**
     * @param brokerId the lab's id for it
     * @param image    the pinned image
     * @param version  what it reported
     * @param nodeId   its identity
     * @param endpoint host:port, no credentials
     */
    public record BrokerRef(String brokerId, String image, String version, String nodeId, String endpoint)
    {
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
