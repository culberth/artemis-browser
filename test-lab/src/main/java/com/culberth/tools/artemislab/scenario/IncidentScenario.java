package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabLimits;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Component;

/** Stable, manually stepped backlog and settings changes for trends and snapshot comparison. */
@Component
public class IncidentScenario implements Recipe
{
    @Override
    public String id()
    {
        return "INCIDENT";
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        String q = f.name("incident");
        f.requireNew(List.of(q));
        f.createQueue(q);
        return f.await(q, Map.of("messageCount", 0L));
    }

    @Override
    public List<Step> steps(LabLimits limits)
    {
        return List.of(
                new Step("grow", "Add ten messages", "Collect a Browser trend sample and snapshot before and after.",
                        List.of()),
                new Step("drain", "Drain up to ten",
                        "Acknowledges up to ten waiting messages; records the actual count.", List.of()),
                new Step("pause", "Pause incident queue", "Changes a snapshot setting without changing its identity.",
                        List.of()),
                new Step("resume", "Resume incident queue", "Restores delivery.", List.of()),
                new Step("recreate", "Recreate incident queue",
                        "Destroys its messages and changes queue ID; history must break.", List.of()));
    }

    @Override
    public String step(Fixture f, String step, Map<String, Integer> params) throws Exception
    {
        String q = f.name("incident");
        f.requireOwned(q, id());
        switch (step)
        {
            case "grow":
                f.limits().checkCount(10);
                f.reserve(100);
                long before = f.management().messageCount(q);
                try (Sender s = f.sender())
                {
                    for (int i = 1; i <= 10; i++)
                        s.send(q, i, s.session().createTextMessage("0123456789"), "text", 10, "incident growth",
                                Sender.Options.PERSISTENT);
                }
                return f.await(q, Map.of("messageCount", before + 10));
            case "drain":
                long count = f.management().messageCount(q);
                int got = f.consume(q, (int) Math.min(count, 10));
                return f.await(q, Map.of("messageCount", count - got)) + "; acknowledged " + got;
            case "pause":
            case "resume":
                f.management().invoke(ResourceNames.QUEUE + q, step);
                return f.assertThat(Boolean.valueOf(step.equals("pause"))
                        .equals(f.management().attribute(ResourceNames.QUEUE + q, "paused")), q + " " + step);
            case "recreate":
                return f.recreateQueue(q);
            default:
                return Recipe.super.step(f, step, params);
        }
    }
}
