package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabLimits;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code LAB-SMOKE}: one owned anycast queue and a bounded, deterministic send. It proves the write path end to end —
 * ownership recorded before creation, every connection verified, bounds enforced, a readiness assertion with a deadline
 * — and is the quickest check that a fresh lab broker works.
 */
@Component
public class SmokeScenario implements Recipe
{

    public static final String ID = "LAB-SMOKE";

    public static String queueName(String runId)
    {
        return "lab." + runId + ".smoke";
    }

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public List<Param> params(LabLimits limits)
    {
        return List.of(new Param("count", "Messages", 10, 1, limits.maxMessages()),
                new Param("bodyBytes", "Body bytes", 100, 0, limits.maxBodyBytes()));
    }

    @Override
    public String run(Fixture fixture, Map<String, Integer> params) throws Exception
    {
        int count = params.get("count");
        int bodyBytes = params.get("bodyBytes");
        String queue = queueName(fixture.runId());
        fixture.requireNew(List.of(queue));
        fixture.reserve((long) count * bodyBytes);
        fixture.createQueue(queue);
        String first = null;
        String last = null;
        try (Sender sender = fixture.sender())
        {
            for (int i = 1; i <= count; i++)
            {
                last = sender.send(queue, i, sender.session().createTextMessage(body(fixture.runId(), i, bodyBytes)),
                        "text", bodyBytes, "", Sender.Options.PERSISTENT);
                if (first == null)
                {
                    first = last;
                }
            }
        }
        return fixture.await(queue, Map.of("messageCount", (long) count)) + ". Sent " + count + " (first " + first
                + ", last " + last + ").";
    }

    /** Deterministic: the same run, sequence and size always give the same body. */
    static String body(String runId, int sequence, int bytes)
    {
        String head = "lab smoke " + runId + " #" + sequence + " ";
        if (head.length() >= bytes)
        {
            return head.substring(0, bytes);
        }
        return head + "x".repeat(bytes - head.length());
    }
}
