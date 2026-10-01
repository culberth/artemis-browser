package com.culberth.tools.artemislab.scenario;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Synthetic typed filters, comparison pairs and mixed real/missing dead-letter origins. */
@Component
public class InvestigationContentScenario implements Recipe
{
    @Override
    public String id()
    {
        return "INVESTIGATION-CONTENT";
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        List<String> queues = List.of("guided", "compare", "triage", "triage-a", "triage-b");
        f.requireNew(queues.stream().map(f::name).toList());
        f.limits().checkCount(12);
        f.limits().checkBodyBytes(1000);
        f.reserve(5000);
        for (String q : queues)
            f.createQueue(f.name(q));
        for (String source : List.of("triage-a", "triage-b"))
            f.addAddressSettings(f.name(source),
                    Map.of("deadLetterAddress", f.name("triage"), "maxDeliveryAttempts", 1));
        try (Sender s = f.sender())
        {
            for (int i = 1; i <= 4; i++)
            {
                var m = s.session().createTextMessage("guided " + i);
                m.setStringProperty("owner", i <= 2 ? "O'Brien" : "plain");
                m.setIntProperty("amount", i * 10);
                m.setBooleanProperty("enabled", i % 2 == 0);
                m.setLongProperty("eventTime", 1790812800000L + (i - 2) * 1000L);
                s.send(f.name("guided"), i, m, "text", 8, "typed properties and UTC boundary",
                        Sender.Options.PERSISTENT);
            }
            List<String> bodies = List.of("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}", "{\"a\":2,\"b\":2}",
                    "x".repeat(1000));
            for (int i = 0; i < bodies.size(); i++)
            {
                String body = bodies.get(i);
                var m = s.session().createTextMessage(body);
                if (i == 2)
                    m.setStringProperty("typed", "7");
                else
                    m.setIntProperty("typed", 7);
                s.send(f.name("compare"), i + 1, m, "text", body.getBytes(StandardCharsets.UTF_8).length,
                        "comparison pair", Sender.Options.PERSISTENT);
            }
            s.send(f.name("compare"), 5, s.session().createObjectMessage("synthetic"), "object", 64, "unsupported body",
                    Sender.Options.PERSISTENT);
            s.send(f.name("triage"), 1, s.session().createTextMessage("no origin"), "text", 9, "missing metadata",
                    Sender.Options.PERSISTENT);
            for (String source : List.of("triage-a", "triage-b"))
                s.send(f.name(source), 1, s.session().createTextMessage("rejected"), "text", 8,
                        "dead-letter from " + source, Sender.Options.PERSISTENT);
        }
        for (String source : List.of("triage-a", "triage-b"))
        {
            f.assertThat(f.rollbackOnce(f.name(source)) == 1, "Received first attempt from " + source);
            f.await(f.name(source), Map.of("messageCount", 0L, "messagesKilled", 1L));
        }
        f.await(f.name("triage"), Map.of("messageCount", 3L));
        f.await(f.name("compare"), Map.of("messageCount", 5L));
        f.await(f.name("guided"), Map.of("messageCount", 4L));
        f.awaitSelected(f.name("guided"), "owner = 'O''Brien'", 2);
        f.awaitSelected(f.name("guided"), "amount >= 30 AND enabled = TRUE", 1);
        f.awaitSelected(f.name("guided"), "eventTime >= 1790812800000", 3);
        return "guided: 2 quoted-name matches, 1 numeric/boolean match, 3 UTC-boundary matches. compare: JSON reorder, changed typed property, long text, unsupported object. triage: two real origins plus one unknown.";
    }
}
