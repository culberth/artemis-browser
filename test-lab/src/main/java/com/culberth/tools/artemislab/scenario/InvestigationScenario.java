package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabLimits;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Exact IDs in waiting, scheduled, held and prepared states, with explicit rollback. */
@Component
public class InvestigationScenario implements Recipe
{
    @Override
    public String id()
    {
        return "INVESTIGATION";
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        List<String> names = List.of("invest-waiting", "invest-scheduled", "invest-held", "invest-xa",
                "invest-xa-target");
        f.requireNew(names.stream().map(f::name).toList());
        f.limits().checkCount(5);
        f.reserve(40);
        f.workers(1);
        for (String name : names)
            f.createQueue(f.name(name));
        try (Sender s = f.sender())
        {
            for (int i = 0; i < 4; i++)
                s.send(f.name(names.get(i)), i + 1, s.session().createTextMessage("find me!"), "text", 8, names.get(i),
                        i == 1 ? Sender.Options.PERSISTENT.withDeliveryDelay(300_000) : Sender.Options.PERSISTENT);
        }
        f.hold("investigation holder", f.clientId("investigator"), f.name("invest-held"), null, 1);
        f.prepareXa(f.name("invest-xa"), f.name("invest-xa-target"));
        f.await(f.name("invest-waiting"), Map.of("messageCount", 1L));
        f.await(f.name("invest-scheduled"), Map.of("messageCount", 1L, "scheduledCount", 1L));
        f.await(f.name("invest-held"), Map.of("messageCount", 1L, "deliveringCount", 1L));
        f.await(f.name("invest-xa"), Map.of("messageCount", 1L, "deliveringCount", 1L, "consumerCount", 0L));
        return "Use the recorded JMS IDs on /search; /transactions lists the prepared branch. Scheduled delivery becomes eligible after five minutes.";
    }

    @Override
    public List<Step> steps(LabLimits limits)
    {
        return List.of(new Step("rollback", "Roll back prepared branch",
                "Returns its receive; discards its send. Browser must not resolve it.", List.of()));
    }

    @Override
    public String step(Fixture f, String step, Map<String, Integer> params) throws Exception
    {
        if (!step.equals("rollback"))
            return Recipe.super.step(f, step, params);
        String result = f.rollbackXa();
        f.await(f.name("invest-xa"), Map.of("messageCount", 1L, "deliveringCount", 0L));
        f.await(f.name("invest-xa-target"), Map.of("messageCount", 0L));
        return result;
    }
}
