package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code SCALE} (E07): many owned queues of small persistent messages, sized by the form and bounded by
 * {@code lab.limits.max-scale-messages} in all. The small preset is the default; the measurements in
 * {@code .claude/memory.md} were taken at 1,000 queues and at 100,000 messages in one queue. Seeding is the slow part —
 * expect minutes at the top of the range.
 */
@Component
public class ScaleScenario implements Recipe
{

    public static final String ID = "SCALE";

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public List<Param> params(LabLimits limits)
    {
        return List.of(new Param("queues", "Queues", 10, 1, 1000),
                new Param("perQueue", "Messages per queue", 100, 1, limits.maxMessages()),
                new Param("bodyBytes", "Body bytes", 200, 1, limits.maxBodyBytes()));
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        int queues = params.get("queues");
        int perQueue = params.get("perQueue");
        int bodyBytes = params.get("bodyBytes");
        long total = (long) queues * perQueue;
        if (total > f.limits().maxScaleMessages())
        {
            throw new LabException(queues + " x " + perQueue + " = " + total + " messages; the limit is "
                    + f.limits().maxScaleMessages() + " (lab.limits.max-scale-messages).");
        }
        List<String> names = new ArrayList<>();
        for (int q = 1; q <= queues; q++)
        {
            names.add(f.name(String.format("scale-%04d", q)));
        }
        f.requireNew(names);
        f.reserve(total * bodyBytes);
        for (String name : names)
        {
            f.createQueue(name);
        }
        String body = "s".repeat(bodyBytes);
        try (Sender s = f.sender())
        {
            for (String name : names)
            {
                for (int i = 1; i <= perQueue; i++)
                {
                    s.send(name, i, s.session().createTextMessage(body), "text", bodyBytes, "",
                            Sender.Options.PERSISTENT);
                }
                f.job().progress("seeded " + name);
            }
        }
        for (String name : names)
        {
            f.await(name, DeliveryScenario.counts("messageCount", perQueue));
        }
        return "Seeded " + queues + " queues x " + perQueue + " = " + total + " messages of " + bodyBytes
                + " bytes; every count asserted.";
    }
}
