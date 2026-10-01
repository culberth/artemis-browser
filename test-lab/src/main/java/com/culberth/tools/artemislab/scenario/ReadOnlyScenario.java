package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import com.culberth.tools.artemislab.run.RunManifest.Reading;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code READONLY} (E04): the read-only guarantee checked from outside Artemis Browser. Running it reads every queue
 * this run owns — messages, delivering, scheduled, added, acknowledged, expired, killed — as a baseline; then use every
 * Browser page on those queues, and <em>Compare</em> reads them again. Any difference is listed and fails the check.
 *
 * <p>
 * Only meaningful on a settled fixture: refused while this run's traffic is running, and anything on a timer (a
 * scheduled message falling due, a TTL) shows as a difference that Browser did not cause — the case says to use a
 * stable fixture for exactly that reason.
 */
@Component
public class ReadOnlyScenario implements Recipe
{

    public static final String ID = "READONLY";
    static final List<String> ATTRIBUTES = List.of("messageCount", "deliveringCount", "scheduledCount", "messagesAdded",
            "messagesAcknowledged", "messagesExpired", "messagesKilled");

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public List<Step> steps(LabLimits limits)
    {
        return List.of(new Step("compare", "Compare with baseline",
                "Reads every owned queue again and lists any counter that changed since the baseline.", List.of()),
                new Step("retake", "Retake baseline", "Replaces the baseline with a fresh reading.", List.of()));
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        return take(f);
    }

    @Override
    public String step(Fixture f, String stepId, Map<String, Integer> params) throws Exception
    {
        return switch (stepId)
        {
            case "retake" -> take(f);
            case "compare" -> compare(f);
            default -> Recipe.super.step(f, stepId, params);
        };
    }

    private static String take(Fixture f) throws Exception
    {
        settled(f);
        Map<String, Map<String, Long>> queues = f.readQueues(ATTRIBUTES);
        if (queues.isEmpty())
        {
            throw new LabException("This run owns no queues yet: run the recipes to check first, then READONLY.");
        }
        f.saveBaseline(new Reading(Instant.now(), queues));
        String text = "Baseline of " + queues.size() + " queues taken. Now use Artemis Browser on them, then compare.";
        f.record("baseline", text);
        return text;
    }

    private static String compare(Fixture f) throws Exception
    {
        Reading baseline = f.baseline();
        if (baseline == null)
        {
            throw new LabException("No baseline: run READONLY first.");
        }
        settled(f);
        Map<String, Map<String, Long>> now = f.readQueues(ATTRIBUTES);
        List<String> changes = new ArrayList<>();
        for (var queue : baseline.queues().entrySet())
        {
            Map<String, Long> after = now.get(queue.getKey());
            if (after == null)
            {
                changes.add(queue.getKey() + " no longer exists");
                continue;
            }
            for (var value : queue.getValue().entrySet())
            {
                Long later = after.get(value.getKey());
                if (!value.getValue().equals(later))
                {
                    changes.add(queue.getKey() + " " + value.getKey() + " " + value.getValue() + " -> " + later);
                }
            }
        }
        String text = changes.isEmpty()
                ? "No counter changed on " + baseline.queues().size() + " queues since the baseline of "
                        + baseline.takenAt() + "."
                : changes.size() + " change(s) since " + baseline.takenAt() + ": " + String.join("; ", changes);
        return f.assertThat(changes.isEmpty(), text);
    }

    private static void settled(Fixture f)
    {
        if (f.trafficRunning())
        {
            throw new LabException("Traffic is running in this run; stop it first (RATES, Stop traffic).");
        }
    }
}
