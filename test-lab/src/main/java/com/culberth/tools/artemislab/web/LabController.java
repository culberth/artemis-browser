package com.culberth.tools.artemislab.web;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import com.culberth.tools.artemislab.broker.BrokerService;
import com.culberth.tools.artemislab.broker.LabBroker;
import com.culberth.tools.artemislab.job.Job;
import com.culberth.tools.artemislab.job.JobRunner;
import com.culberth.tools.artemislab.run.CaseResult;
import com.culberth.tools.artemislab.run.RunManifest;
import com.culberth.tools.artemislab.run.RunService;
import com.culberth.tools.artemislab.run.RunStore;
import com.culberth.tools.artemislab.scenario.ScenarioCatalog;
import com.culberth.tools.artemislab.scenario.SmokeScenario;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The lab's pages. Every change is a POST carrying a one-time token (duplicate submissions return the first job) and
 * redirects back to a page that shows the job's progress; refusals come back as a message, never a half-done action.
 */
@Controller
public class LabController
{

    private final BrokerService brokers;
    private final RunService runs;
    private final RunStore store;
    private final JobRunner runner;
    private final ScenarioCatalog catalog;
    private final LabLimits limits;

    public LabController(BrokerService brokers, RunService runs, RunStore store, JobRunner runner,
            ScenarioCatalog catalog, LabLimits limits)
    {
        this.brokers = brokers;
        this.runs = runs;
        this.store = store;
        this.runner = runner;
        this.catalog = catalog;
        this.limits = limits;
    }

    @GetMapping("/")
    public String index(Model model)
    {
        LabBroker broker = brokers.current().orElse(null);
        List<Job> brokerJobs = runner.jobs(JobRunner.BROKER_SCOPE);
        model.addAttribute("broker", broker);
        model.addAttribute("images", brokers.supportedImages());
        model.addAttribute("brokerPort", brokers.properties().port());
        model.addAttribute("brokerJobs", brokerJobs.stream().limit(5).toList());
        model.addAttribute("busy", runner.active(JobRunner.BROKER_SCOPE));
        model.addAttribute("leftovers", brokers.leftovers());
        model.addAttribute("runs", store.list());
        model.addAttribute("affected", broker == null ? List.of() : runs.runsOn(broker));
        model.addAttribute("token", JobRunner.newToken());
        return "index";
    }

    @PostMapping("/broker/provision")
    public String provision(@RequestParam String image, @RequestParam String token, RedirectAttributes redirect)
    {
        return act(redirect, "/", () ->
        {
            if (!brokers.properties().supports(image))
            {
                throw new LabException("Not a supported image: " + image + ".");
            }
            runner.submit(JobRunner.BROKER_SCOPE, token, "provision " + image, job ->
            {
                LabBroker started = brokers.provision(image);
                return "Broker " + started.brokerId() + " ready: Artemis " + started.reportedVersion() + ", node "
                        + started.nodeId() + ", at " + started.endpoint() + ".";
            });
        });
    }

    @PostMapping("/broker/stop")
    public String stopBroker(@RequestParam String token, @RequestParam(defaultValue = "false") boolean confirm,
            RedirectAttributes redirect)
    {
        return act(redirect, "/", () ->
        {
            if (!confirm)
            {
                throw new LabException("Tick the confirmation: stopping the broker removes everything on it.");
            }
            runs.stopBroker(token);
        });
    }

    @PostMapping("/leftovers/remove")
    public String removeLeftover(@RequestParam String containerId, RedirectAttributes redirect)
    {
        return act(redirect, "/", () -> brokers.removeLeftover(containerId));
    }

    @GetMapping("/catalog")
    public String catalog(Model model)
    {
        model.addAttribute("catalog", catalog);
        return "catalog";
    }

    @PostMapping("/runs")
    public String createRun(@RequestParam(defaultValue = "") String browserCommit, RedirectAttributes redirect)
    {
        try
        {
            RunManifest run = runs.create(browserCommit);
            return "redirect:/runs/" + run.runId();
        }
        catch (LabException e)
        {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        }
    }

    @GetMapping("/runs/{runId}")
    public String run(@PathVariable String runId, Model model)
    {
        RunManifest run = store.get(runId);
        List<Job> jobs = runner.jobs(runId);
        model.addAttribute("run", run);
        model.addAttribute("jobs", jobs);
        model.addAttribute("busy", jobs.stream().anyMatch(Job::active));
        model.addAttribute("broker", brokers.current().orElse(null));
        model.addAttribute("smoke", catalog.find(SmokeScenario.ID).orElseThrow());
        model.addAttribute("smokeQueue", SmokeScenario.queueName(runId));
        model.addAttribute("cases", catalog.cases());
        model.addAttribute("statuses", CaseResult.Status.values());
        model.addAttribute("limits", limits);
        model.addAttribute("token", JobRunner.newToken());
        return "run";
    }

    @PostMapping("/runs/{runId}/scenarios/{scenarioId}")
    public String runScenario(@PathVariable String runId, @PathVariable String scenarioId,
            @RequestParam(defaultValue = "10") int count, @RequestParam(defaultValue = "100") int bodyBytes,
            @RequestParam String token, RedirectAttributes redirect)
    {
        return act(redirect, "/runs/" + runId, () -> runs.runScenario(runId, scenarioId, count, bodyBytes, token));
    }

    @PostMapping("/runs/{runId}/jobs/{jobId}/cancel")
    public String cancel(@PathVariable String runId, @PathVariable String jobId, RedirectAttributes redirect)
    {
        return act(redirect, "/runs/" + runId, () -> runner.cancel(jobId));
    }

    @PostMapping("/runs/{runId}/cleanup")
    public String cleanup(@PathVariable String runId, @RequestParam String token, RedirectAttributes redirect)
    {
        return act(redirect, "/runs/" + runId, () -> runs.cleanup(runId, token));
    }

    @PostMapping("/runs/{runId}/results")
    public String recordResult(@PathVariable String runId, @RequestParam String caseId,
            @RequestParam CaseResult.Status fixture, @RequestParam CaseResult.Status browser,
            @RequestParam(defaultValue = "") String notes, RedirectAttributes redirect)
    {
        return act(redirect, "/runs/" + runId, () -> runs.recordResult(runId, caseId, fixture, browser, notes));
    }

    /** The whole manifest as a file. It has no password fields; the broker's password is never written to it. */
    @GetMapping("/runs/{runId}/manifest.json")
    public ResponseEntity<byte[]> manifest(@PathVariable String runId)
    {
        RunManifest run = store.get(runId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lab-run-" + run.runId() + ".json\"")
                .contentType(MediaType.APPLICATION_JSON).body(store.json(run));
    }

    @FunctionalInterface
    private interface Action
    {
        void run() throws Exception;
    }

    private static String act(RedirectAttributes redirect, String back, Action action)
    {
        try
        {
            action.run();
        }
        catch (LabException e)
        {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            redirect.addFlashAttribute("error", "Interrupted.");
        }
        catch (Exception e)
        {
            redirect.addFlashAttribute("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return "redirect:" + back;
    }
}
