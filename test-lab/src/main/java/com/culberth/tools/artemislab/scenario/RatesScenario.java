package com.culberth.tools.artemislab.scenario;

import static com.culberth.tools.artemislab.scenario.DeliveryScenario.counts;

import com.culberth.tools.artemislab.LabLimits;
import com.culberth.tools.artemislab.worker.OpenClient;
import com.culberth.tools.artemislab.worker.Traffic;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code RATES} (A04–A06): clients to drill into, and traffic whose rate is known.
 *
 * <ul>
 * <li>A04 — queue {@code clients} with three idle workers: producer {@code lab-<run>-producer} on two sessions, an
 * anonymous consumer, and consumer {@code lab-<run>-consumer} on three sessions.</li>
 * <li>A05 — queue {@code rate}, driven by the steps: a producer and a consumer at a chosen rate for a chosen time, each
 * reporting what it actually sent or received.</li>
 * <li>A06 — the <em>Recreate rate queue</em> step destroys and recreates {@code rate}, so its counters start again
 * under a new id; restarting the broker (lab page) is the other discontinuity.</li>
 * </ul>
 */
@Component
public class RatesScenario implements Recipe
{

    public static final String ID = "RATES";
    static final int BODY = 100;

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public List<Step> steps(LabLimits limits)
    {
        List<Param> traffic = List.of(new Param("perSecond", "Per second", 20, 1, limits.maxRate()),
                new Param("seconds", "Seconds", 60, 1, (int) limits.maxTraffic().toSeconds()));
        return List.of(
                new Step("produce", "Start producer",
                        "Sends 100-byte messages to rate at this rate for this long, persistent.", traffic),
                new Step("consume", "Start consumer",
                        "Receives and acknowledges from rate at up to this rate for this long.", traffic),
                new Step("stop", "Stop traffic", "Stops this run's producers and consumers; counts are kept.",
                        List.of()),
                new Step("recreate", "Recreate rate queue",
                        "Stops traffic, destroys rate and creates it again: its counters restart under a new id.",
                        List.of()));
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        String clients = f.name("clients");
        String rate = f.name("rate");
        f.requireNew(List.of(clients, rate));
        f.workers(3);
        f.createQueue(clients);
        f.createQueue(rate);
        f.openClient("identified producer, 2 sessions", f.clientId("producer"), clients, OpenClient.Role.PRODUCER, 2);
        f.openClient("anonymous consumer", null, clients, OpenClient.Role.CONSUMER, 1);
        f.openClient("identified consumer, 3 sessions", f.clientId("consumer"), clients, OpenClient.Role.CONSUMER, 3);
        return f.await(clients, counts("consumerCount", 4)) + ". Three idle clients are open (see Workers); use the "
                + "steps to put traffic on rate.";
    }

    @Override
    public String step(Fixture f, String stepId, Map<String, Integer> params) throws Exception
    {
        String rate = f.name("rate");
        f.requireOwned(rate, ID);
        return switch (stepId)
        {
            case "produce" -> f.traffic(Traffic.Direction.PRODUCE, rate, params.get("perSecond"),
                    Duration.ofSeconds(params.get("seconds")), BODY).description() + ": started.";
            case "consume" -> f.traffic(Traffic.Direction.CONSUME, rate, params.get("perSecond"),
                    Duration.ofSeconds(params.get("seconds")), 0).description() + ": started.";
            case "stop" -> f.stopTraffic();
            case "recreate" -> f.stopTraffic() + " " + f.recreateQueue(rate);
            default -> Recipe.super.step(f, stepId, params);
        };
    }
}
