package com.culberth.tools.artemislab.run;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import com.culberth.tools.artemislab.broker.BrokerService;
import com.culberth.tools.artemislab.broker.LabBroker;
import com.culberth.tools.artemislab.broker.ManagementClient;
import com.culberth.tools.artemislab.broker.TargetGuard;
import com.culberth.tools.artemislab.broker.TargetMismatchException;
import com.culberth.tools.artemislab.job.Job;
import com.culberth.tools.artemislab.job.JobRunner;
import com.culberth.tools.artemislab.run.ActionRecord.Outcome;
import com.culberth.tools.artemislab.run.OwnedResource.Kind;
import com.culberth.tools.artemislab.run.OwnedResource.State;
import com.culberth.tools.artemislab.run.RunManifest.BrokerRef;
import com.culberth.tools.artemislab.run.RunManifest.RunState;
import com.culberth.tools.artemislab.scenario.ScenarioCatalog;
import com.culberth.tools.artemislab.scenario.Fixture;
import com.culberth.tools.artemislab.scenario.Recipe;
import com.culberth.tools.artemislab.worker.HeldConsumer;
import com.culberth.tools.artemislab.worker.Worker;
import com.culberth.tools.artemislab.worker.WorkerRegistry;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;

/**
 * Runs: creating them against the current broker, running scenarios in them, cleaning up exactly what they own, and
 * recording results. Every job writes STARTED and one terminal line to the run's history, so a run's log says what was
 * attempted even when it failed.
 */
@Service
public class RunService
{

    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'-'HHmmss")
            .withZone(ZoneOffset.UTC);

    private final RunStore store;
    private final JobRunner runner;
    private final BrokerService brokers;
    private final ScenarioCatalog catalog;
    private final Map<String, Recipe> recipes;
    private final LabLimits limits;
    private final WorkerRegistry workers;

    public RunService(RunStore store, JobRunner runner, BrokerService brokers, ScenarioCatalog catalog,
            List<Recipe> recipes, LabLimits limits, WorkerRegistry workers)
    {
        this.workers = workers;
        this.store = store;
        this.runner = runner;
        this.brokers = brokers;
        this.catalog = catalog;
        this.recipes = registry(recipes, catalog);
        this.limits = limits;
    }

    /**
     * Recipes by id, which must be exactly the catalog's runnable cards: a card marked runnable with nothing behind it,
     * or a recipe the catalog still calls unimplemented, fails startup rather than a click.
     */
    static Map<String, Recipe> registry(List<Recipe> recipes, ScenarioCatalog catalog)
    {
        Map<String, Recipe> byId = new LinkedHashMap<>();
        for (Recipe recipe : recipes)
        {
            if (byId.put(recipe.id(), recipe) != null)
            {
                throw new IllegalStateException("Two recipes claim " + recipe.id());
            }
        }
        Set<String> runnable = catalog.scenarios().stream().filter(ScenarioCatalog.Scenario::runnable)
                .map(ScenarioCatalog.Scenario::id).collect(Collectors.toSet());
        if (!runnable.equals(byId.keySet()))
        {
            throw new IllegalStateException(
                    "Catalog runnable cards " + runnable + " do not match the implemented recipes " + byId.keySet());
        }
        return Map.copyOf(byId);
    }

    /** The runnable scenarios, in catalog order, with their parameters. */
    public List<RunnableScenario> runnable()
    {
        return catalog.scenarios().stream().filter(ScenarioCatalog.Scenario::runnable)
                .map(card -> new RunnableScenario(card, recipes.get(card.id()).params(limits),
                        recipes.get(card.id()).steps(limits)))
                .toList();
    }

    /**
     * A runnable catalog card and its form parameters.
     *
     * @param card   the catalog card
     * @param params what its form asks for
     * @param steps  follow-up actions once it has run
     */
    public record RunnableScenario(ScenarioCatalog.Scenario card, List<Recipe.Param> params, List<Recipe.Step> steps)
    {
    }

    /** A new run bound to the current broker by identity. */
    public RunManifest create(String browserCommit)
    {
        LabBroker broker = brokers.current()
                .orElseThrow(() -> new LabException("Provision a lab broker before starting a run."));
        String runId = "r" + RUN_STAMP.format(Instant.now()) + "-"
                + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()).substring(0, 4);
        String commit = browserCommit == null ? "" : browserCommit.strip();
        if (commit.length() > 80)
        {
            throw new LabException("The Browser commit field takes a revision, at most 80 characters.");
        }
        RunManifest manifest = RunManifest.open(runId, catalog.revision(), Instant.now(),
                ZoneId.systemDefault().getId(), commit, new BrokerRef(broker.brokerId(), broker.image(),
                        broker.reportedVersion(), broker.nodeId(), broker.endpoint(), broker.profile().name()));
        for (ScenarioCatalog.Case c : catalog.cases())
            manifest = manifest.withResult(new CaseResult(c.id(), CaseResult.Status.NOT_RUN, CaseResult.Status.NOT_RUN,
                    "", manifest.createdAt()));
        store.create(manifest);
        return store.update(runId, r -> r.withAction(ActionRecord.now("", "create run", Outcome.SUCCEEDED,
                "bound to broker " + broker.brokerId() + ", node " + broker.nodeId())));
    }

    /** Runs a catalogued recipe in the run, with its parameters validated server-side. */
    public Job runScenario(String runId, String scenarioId, Map<String, String> form, String token)
    {
        RunManifest run = store.get(runId);
        catalog.runnable(scenarioId);
        if (!run.acceptsScenarios())
        {
            throw new LabException("Run " + runId + " is " + run.state() + "; it accepts no more scenarios.");
        }
        Recipe recipe = recipes.get(scenarioId);
        if (!recipe.profiles().isEmpty()
                && recipe.profiles().stream().noneMatch(p -> p.name().equals(run.broker().profile())))
        {
            throw new LabException(scenarioId + " needs a broker provisioned with the "
                    + recipe.profiles().stream().map(com.culberth.tools.artemislab.broker.BrokerProfile::label).toList()
                    + " profile; this run's broker is " + run.broker().profile() + ". Nothing was run.");
        }
        Map<String, Integer> params = parameters(recipe.params(limits), form);
        LabBroker broker = boundBroker(run);
        String action = params.isEmpty() ? scenarioId : scenarioId + " " + params;
        return runner.submit(runId, token, action, job -> recorded(runId, job, action, () ->
        {
            try (Fixture fixture = fixture(job, runId, broker))
            {
                return recipe.run(fixture, params);
            }
        }));
    }

    /** Runs one of a recipe's follow-up steps in the run, once the recipe has prepared it. */
    public Job runStep(String runId, String scenarioId, String stepId, Map<String, String> form, String token)
    {
        RunManifest run = store.get(runId);
        catalog.runnable(scenarioId);
        if (!run.acceptsScenarios())
        {
            throw new LabException("Run " + runId + " is " + run.state() + "; it accepts no more steps.");
        }
        Recipe recipe = recipes.get(scenarioId);
        Recipe.Step step = recipe.steps(limits).stream().filter(s -> s.id().equals(stepId)).findFirst()
                .orElseThrow(() -> new LabException(scenarioId + " has no step " + stepId + "."));
        Map<String, Integer> params = parameters(step.params(), form);
        LabBroker broker = boundBroker(run);
        String action = scenarioId + ": " + step.label() + (params.isEmpty() ? "" : " " + params);
        return runner.submit(runId, token, action, job -> recorded(runId, job, action, () ->
        {
            try (Fixture fixture = fixture(job, runId, broker))
            {
                return recipe.step(fixture, stepId, params);
            }
        }));
    }

    /**
     * Acts on one of the run's workers: {@code acknowledge} what a held consumer holds, or {@code stop} it — which
     * returns anything it held to its queue unacknowledged.
     */
    public Job workerAction(String runId, String workerId, String action, String token)
    {
        Worker worker = workers.find(runId, workerId)
                .orElseThrow(() -> new LabException("No worker " + workerId + " in run " + runId + "."));
        String label = action + " " + worker.description();
        return runner.submit(runId, token, label, job -> recorded(runId, job, label, () -> switch (action)
        {
            case "acknowledge" ->
            {
                if (!(worker instanceof HeldConsumer held))
                {
                    throw new LabException(worker.description() + " holds nothing to acknowledge.");
                }
                yield "Acknowledged " + held.acknowledge() + " held by " + worker.description() + ".";
            }
            case "stop" -> worker.stop("stopped from the run page");
            default -> throw new LabException("Unknown worker action " + action + ".");
        }));
    }

    public List<Worker> workers(String runId)
    {
        return workers.of(runId);
    }

    /**
     * Takes the broker down for {@code seconds}, then starts it again in place (E03). As with a restart, every worker
     * is stopped first and the broker must come back with the same node id.
     */
    public Job interruptBroker(int seconds, String token)
    {
        if (seconds < 1 || seconds > 300)
        {
            throw new LabException("Interrupt for 1 to 300 seconds; got " + seconds + ".");
        }
        LabBroker broker = brokers.current().orElseThrow(() -> new LabException("No lab broker is running."));
        return runner.submit(JobRunner.BROKER_SCOPE, token,
                "interrupt broker " + broker.brokerId() + " for " + seconds + "s", job ->
                {
                    List<String> stopped = workers.stopEverything("broker interrupted");
                    LabBroker back = brokers.interrupt(java.time.Duration.ofSeconds(seconds));
                    String outcome = "Broker " + broker.brokerId() + " was down for " + seconds + "s and is back; node "
                            + back.nodeId() + " unchanged. Stopped " + stopped.size() + " worker(s) first.";
                    for (RunManifest run : runsOn(broker))
                    {
                        store.update(run.runId(), r -> r.withAction(
                                ActionRecord.now(job.id(), "interrupt broker", Outcome.SUCCEEDED, outcome)));
                    }
                    return outcome;
                });
    }

    /**
     * Restarts the broker in place. Every live worker is stopped first — held deliveries return to their queues — and
     * each open run on the broker records the restart, which is a discontinuity for any rate measured across it.
     */
    public Job restartBroker(String token)
    {
        LabBroker broker = brokers.current().orElseThrow(() -> new LabException("No lab broker is running."));
        return runner.submit(JobRunner.BROKER_SCOPE, token, "restart broker " + broker.brokerId(), job ->
        {
            List<String> stopped = workers.stopEverything("broker restart");
            LabBroker restarted = brokers.restart();
            String outcome = "Restarted broker " + broker.brokerId() + "; node " + restarted.nodeId()
                    + " unchanged. Stopped " + stopped.size() + " worker(s) first.";
            for (RunManifest run : runsOn(broker))
            {
                store.update(run.runId(),
                        r -> r.withAction(ActionRecord.now(job.id(), "restart broker", Outcome.SUCCEEDED, outcome)));
            }
            return outcome;
        });
    }

    private Fixture fixture(Job job, String runId, LabBroker broker) throws JMSException
    {
        return Fixture.open(job, runId, brokers.guard(broker), brokers.guard(broker, true), store, limits, workers,
                broker.profile(), user -> brokers.guardAs(broker, user));
    }

    /** Each declared parameter from the form, or its default; anything else in the form is ignored. */
    static Map<String, Integer> parameters(List<Recipe.Param> declared, Map<String, String> form)
    {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (Recipe.Param param : declared)
        {
            String raw = form == null ? null : form.get(param.name());
            int value;
            if (raw == null || raw.isBlank())
            {
                value = param.defaultValue();
            }
            else
            {
                try
                {
                    value = Integer.parseInt(raw.strip());
                }
                catch (NumberFormatException e)
                {
                    throw new LabException(param.label() + " must be a whole number; got \"" + raw + "\".");
                }
            }
            if (value < param.min() || value > param.max())
            {
                throw new LabException(param.label() + " must be between " + param.min() + " and " + param.max()
                        + "; got " + value + ".");
            }
            values.put(param.name(), value);
        }
        return values;
    }

    /**
     * Stops the run's workers, then removes exactly the resources its manifest lists and proves each gone. Anything
     * that cannot be removed leaves the run {@link RunState#CLEANUP_FAILED}, which blocks it until a retry succeeds.
     */
    public Job cleanup(String runId, String token) throws InterruptedException
    {
        RunManifest run = store.get(runId);
        if (run.state() == RunState.CLEANED || run.state() == RunState.BROKER_GONE)
        {
            throw new LabException("Run " + runId + " is " + run.state() + "; there is nothing to clean up.");
        }
        runner.cancelAll(runId);
        for (String line : workers.stopAll(runId, "cleanup"))
        {
            store.update(runId, r -> r.withAction(ActionRecord.now("", "worker", Outcome.SUCCEEDED, line)));
        }
        if (!runner.awaitIdle(runId, limits.operationTimeout()))
        {
            throw new LabException("A job in this run did not stop within " + limits.operationTimeout()
                    + "; cleanup was not started.");
        }
        LabBroker broker = boundBroker(run);
        return runner.submit(runId, token, "clean up", job -> recorded(runId, job, "clean up", () ->
        {
            store.update(runId, r -> r.withState(RunState.CLEANING));
            return removeOwned(runId, brokers.guard(broker));
        }));
    }

    /**
     * Stops the broker, which discards everything on it: open runs bound to it record their resources as removed with
     * the broker. Refused while any job is running.
     */
    public Job stopBroker(String token)
    {
        LabBroker broker = brokers.current().orElseThrow(() -> new LabException("No lab broker is running."));
        return runner.submit(JobRunner.BROKER_SCOPE, token, "stop broker " + broker.brokerId(), job ->
        {
            workers.stopEverything("broker stopped");
            for (RunManifest run : runsOn(broker))
            {
                store.update(run.runId(),
                        r -> r.withResources(res -> res.state().mayExist()
                                ? res.with(State.REMOVED_WITH_BROKER, "broker stopped " + Instant.now())
                                : res).withState(RunState.CLEANED)
                                .withAction(ActionRecord.now(job.id(), "stop broker", Outcome.SUCCEEDED,
                                        "The broker and everything on it were removed.")));
            }
            brokers.stop();
            return "Stopped and removed broker " + broker.brokerId() + ".";
        });
    }

    /** Open or failed-cleanup runs whose resources a broker stop would discard. */
    public List<RunManifest> runsOn(LabBroker broker)
    {
        return store.list().stream().filter(r -> r.broker().brokerId().equals(broker.brokerId()))
                .filter(r -> r.state() == RunState.OPEN || r.state() == RunState.CLEANUP_FAILED
                        || r.state() == RunState.CLEANING)
                .toList();
    }

    public RunManifest recordResult(String runId, String caseId, CaseResult.Status fixture, CaseResult.Status browser,
            String notes)
    {
        if (!catalog.knowsCase(caseId))
        {
            throw new LabException("Not a procedure case id: " + caseId + ".");
        }
        String text = notes == null ? "" : notes.strip();
        if (text.length() > 4000)
        {
            throw new LabException("Notes are limited to 4000 characters; link longer evidence instead.");
        }
        CaseResult result = new CaseResult(caseId, fixture, browser, text, Instant.now());
        return store.update(runId, r -> r.withResult(result).withAction(ActionRecord.now("", "record " + caseId,
                Outcome.SUCCEEDED, "fixture " + fixture + ", Browser " + browser)));
    }

    /** The broker a run is bound to, which must still be the lab's current one. */
    private LabBroker boundBroker(RunManifest run)
    {
        LabBroker broker = brokers.current()
                .orElseThrow(() -> new LabException("No lab broker is running; run " + run.runId() + " cannot act."));
        if (!broker.brokerId().equals(run.broker().brokerId()) || !broker.nodeId().equals(run.broker().nodeId()))
        {
            throw new LabException("Run " + run.runId() + " belongs to broker " + run.broker().brokerId()
                    + ", not the current one (" + broker.brokerId() + "). Nothing was sent.");
        }
        return broker;
    }

    private String removeOwned(String runId, TargetGuard guard) throws JMSException
    {
        List<String> failures = new ArrayList<>();
        // Prepared work survives disconnects. Resolve it before deleting any queue it holds.
        for (OwnedResource resource : store.get(runId).resources())
        {
            if (resource.kind() != Kind.XA_BRANCH || !resource.state().mayExist())
                continue;
            try
            {
                new com.culberth.tools.artemislab.broker.LabXid(resource.name()).rollback(guard);
                store.update(runId, r -> r.withResource(resource.with(State.DELETED, "rolled back during cleanup")));
            }
            catch (Exception e)
            {
                store.update(runId, r -> r.withResource(resource.with(State.DELETE_FAILED, e.getMessage()))
                        .withState(RunState.CLEANUP_FAILED));
                throw new LabException("Prepared branch cleanup failed; queues preserved: " + resource.name(), e);
            }
        }
        try (Connection connection = guard.open();
                ManagementClient management = new ManagementClient(connection, limits.operationTimeout()))
        {
            // Diverts, then queues, then addresses: an address with a binding cannot go first. Settings last, so the
            // addresses keep their own behaviour until they are gone.
            for (Kind kind : List.of(Kind.DIVERT, Kind.QUEUE, Kind.ADDRESS, Kind.ADDRESS_SETTINGS))
            {
                for (OwnedResource resource : store.get(runId).resources())
                {
                    if (resource.kind() != kind || !resource.state().mayExist())
                    {
                        continue;
                    }
                    OwnedResource outcome = remove(management, resource);
                    if (outcome.state() == State.DELETE_FAILED)
                    {
                        failures.add(resource.name() + ": " + outcome.detail());
                    }
                    store.update(runId, r -> r.withResource(outcome));
                }
            }
        }
        if (failures.isEmpty())
        {
            store.update(runId, r -> r.withState(RunState.CLEANED));
            return "Every owned resource is gone.";
        }
        store.update(runId, r -> r.withState(RunState.CLEANUP_FAILED));
        throw new LabException("Cleanup left " + failures.size() + " resource(s): " + String.join("; ", failures));
    }

    private static OwnedResource remove(ManagementClient management, OwnedResource resource) throws JMSException
    {
        try
        {
            if (resource.kind() == Kind.ADDRESS_SETTINGS)
            {
                // Settings for an exact owned name; once removed, the broker resolves the address against its
                // wildcards again. There is no listing to prove it by, so the broker's acceptance is the evidence.
                management.invoke(ResourceNames.BROKER, "removeAddressSettings", resource.name());
                return resource.with(State.DELETED, "removed " + Instant.now());
            }
            if (!listed(management, resource))
            {
                return resource.state() == State.PLANNED ? resource.with(State.NOT_CREATED, "never created")
                        : resource.with(State.DELETED, "already gone");
            }
            switch (resource.kind())
            {
                case DIVERT -> management.invoke(ResourceNames.BROKER, "destroyDivert", resource.name());
                case QUEUE -> management.invoke(ResourceNames.BROKER, "destroyQueue", resource.name(), true, false);
                default -> management.invoke(ResourceNames.BROKER, "deleteAddress", resource.name());
            }
            return listed(management, resource) ? resource.with(State.DELETE_FAILED, "still listed after deletion")
                    : resource.with(State.DELETED, "deleted " + Instant.now());
        }
        catch (LabException e)
        {
            return resource.with(State.DELETE_FAILED, e.getMessage());
        }
    }

    private static boolean listed(ManagementClient management, OwnedResource resource) throws JMSException
    {
        return switch (resource.kind())
        {
            case QUEUE -> management.queueNames().contains(resource.name());
            case DIVERT -> management.divertNames().contains(resource.name());
            default -> management.addressNames().contains(resource.name());
        };
    }

    @FunctionalInterface
    private interface Step
    {
        String run() throws Exception;
    }

    /** Writes STARTED, runs, then exactly one terminal line. */
    private String recorded(String runId, Job job, String action, Step body) throws Exception
    {
        store.update(runId, r -> r.withAction(ActionRecord.now(job.id(), action, Outcome.STARTED, "")));
        try
        {
            String outcome = body.run();
            store.update(runId, r -> r.withAction(ActionRecord.now(job.id(), action, Outcome.SUCCEEDED, outcome)));
            return outcome;
        }
        catch (CancellationException | InterruptedException e)
        {
            store.update(runId, r -> r.withAction(ActionRecord.now(job.id(), action, Outcome.CANCELLED,
                    "Cancelled; what was already sent stays and is listed in the resources.")));
            throw e;
        }
        catch (TargetMismatchException e)
        {
            store.update(runId, r -> r.withAction(ActionRecord.now(job.id(), action, Outcome.REFUSED, e.getMessage())));
            throw e;
        }
        catch (Exception e)
        {
            if (job.cancelRequested())
            {
                store.update(runId, r -> r.withAction(
                        ActionRecord.now(job.id(), action, Outcome.CANCELLED, "Cancelled: " + e.getMessage())));
            }
            else
            {
                store.update(runId,
                        r -> r.withAction(ActionRecord.now(job.id(), action, Outcome.FAILED, e.getMessage())));
            }
            throw e;
        }
    }
}
