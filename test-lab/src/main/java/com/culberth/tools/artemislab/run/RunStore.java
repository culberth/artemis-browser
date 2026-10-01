package com.culberth.tools.artemislab.run;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import com.culberth.tools.artemislab.run.ActionRecord.Outcome;
import com.culberth.tools.artemislab.run.RunManifest.RunState;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Run manifests, one JSON file each under {@code lab.data-dir/runs}, written whole on every change.
 *
 * <p>
 * Bounded by {@code lab.limits.max-runs}: past it the oldest finished runs are pruned, and if every run is still open a
 * new one is refused. On startup, runs from an earlier lab process are reconciled — an action that started and never
 * finished is marked interrupted, and an open run is marked {@link RunState#BROKER_GONE}, since its broker belonged to
 * that process. Nothing is resumed.
 */
@Component
public class RunStore
{

    private static final Logger LOG = LoggerFactory.getLogger(RunStore.class);

    private final Path directory;
    private final int maxRuns;
    private final JsonMapper mapper;
    private final Map<String, RunManifest> runs = new LinkedHashMap<>();

    public RunStore(@Value("${lab.data-dir}") Path dataDir, LabLimits limits)
    {
        this.directory = dataDir.resolve("runs");
        this.maxRuns = limits.maxRuns();
        // Jackson 3 fails a record on a missing primitive and on unknown properties by default; a manifest written by
        // an older lab must still load.
        this.mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        load();
    }

    public synchronized List<RunManifest> list()
    {
        return runs.values().stream().sorted(Comparator.comparing(RunManifest::createdAt).reversed()).toList();
    }

    public synchronized Optional<RunManifest> find(String runId)
    {
        return Optional.ofNullable(runs.get(runId));
    }

    public RunManifest get(String runId)
    {
        return find(runId).orElseThrow(() -> new LabException("No run " + runId + "."));
    }

    public synchronized RunManifest create(RunManifest manifest)
    {
        if (runs.containsKey(manifest.runId()))
        {
            throw new LabException("Run " + manifest.runId() + " already exists.");
        }
        prune();
        if (runs.size() >= maxRuns)
        {
            throw new LabException("The lab keeps at most " + maxRuns + " runs and none of them is finished. "
                    + "Clean up a run before starting another.");
        }
        write(manifest);
        runs.put(manifest.runId(), manifest);
        return manifest;
    }

    /** Applies a change and writes it before returning; the returned manifest is what is on disk. */
    public synchronized RunManifest update(String runId, UnaryOperator<RunManifest> change)
    {
        RunManifest next = change.apply(get(runId));
        write(next);
        runs.put(runId, next);
        return next;
    }

    public byte[] json(RunManifest manifest)
    {
        return mapper.writeValueAsBytes(manifest);
    }

    Path directory()
    {
        return directory;
    }

    private void load()
    {
        try
        {
            Files.createDirectories(directory);
            try (Stream<Path> files = Files.list(directory))
            {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList())
                {
                    try
                    {
                        RunManifest manifest = reconcile(mapper.readValue(file.toFile(), RunManifest.class));
                        write(manifest);
                        runs.put(manifest.runId(), manifest);
                    }
                    catch (RuntimeException e)
                    {
                        LOG.warn("Skipping unreadable run manifest {}: {}", file, e.getMessage());
                    }
                }
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Cannot use the lab data directory " + directory, e);
        }
    }

    /** A run read at startup was written by an earlier lab process. */
    static RunManifest reconcile(RunManifest manifest)
    {
        RunManifest next = manifest;
        Set<String> finished = new HashSet<>();
        for (ActionRecord action : manifest.history())
        {
            if (action.outcome().terminal() && !action.jobId().isBlank())
            {
                finished.add(action.jobId());
            }
        }
        for (ActionRecord action : manifest.history())
        {
            if (action.outcome() == Outcome.STARTED && !finished.contains(action.jobId()))
            {
                next = next.withAction(ActionRecord.now(action.jobId(), action.action(), Outcome.INTERRUPTED,
                        "The lab stopped while this was running; its effects were not confirmed."));
                finished.add(action.jobId());
            }
        }
        if (next.state() == RunState.OPEN || next.state() == RunState.CLEANING)
        {
            next = next.withState(RunState.BROKER_GONE).withAction(ActionRecord.now("", "lab restart",
                    Outcome.INTERRUPTED, "This run's broker belonged to the previous lab process and is not reused. "
                            + "Any lab-labelled container still running is listed as a leftover on the lab page."));
        }
        return next;
    }

    private void prune()
    {
        List<RunManifest> finished = runs.values().stream()
                .filter(r -> r.state() == RunState.CLEANED || r.state() == RunState.BROKER_GONE)
                .sorted(Comparator.comparing(RunManifest::createdAt)).toList();
        for (RunManifest oldest : finished)
        {
            if (runs.size() < maxRuns)
            {
                return;
            }
            try
            {
                Files.deleteIfExists(file(oldest.runId()));
                runs.remove(oldest.runId());
            }
            catch (IOException e)
            {
                LOG.warn("Could not prune run {}: {}", oldest.runId(), e.getMessage());
                return;
            }
        }
    }

    private void write(RunManifest manifest)
    {
        Path target = file(manifest.runId());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try
        {
            Files.write(temporary, mapper.writeValueAsBytes(manifest));
            try
            {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e)
        {
            throw new LabException("Could not write the run manifest " + target + ": " + e.getMessage(), e);
        }
    }

    private Path file(String runId)
    {
        if (!runId.matches("[a-z0-9-]+"))
        {
            throw new LabException("Not a run id: " + runId);
        }
        return directory.resolve(runId + ".json");
    }
}
