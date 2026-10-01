package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.broker.BrokerProfile;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Component;

/**
 * {@code LOW-LIMITS} (P04): broker-wide pressure without filling the host, on a broker provisioned with a reduced
 * limit.
 *
 * <ul>
 * <li><em>Low global memory</em> (global-max-size 2MB): queue {@code memory} gets 150 × 10KB, about three quarters of
 * the global limit; the broker's address memory use must read at least half.</li>
 * <li><em>Disk threshold reached</em> (max-disk-usage 1%): nothing is written to fill the disk — the store already
 * counts as full. The recipe reads disk usage against the threshold and tries one persistent send to {@code disk},
 * recording whether the broker took it.</li>
 * </ul>
 */
@Component
public class LowLimitsScenario implements Recipe
{

    public static final String ID = "LOW-LIMITS";
    static final int MESSAGES = 150;
    static final int BODY = 10_000;

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public Set<BrokerProfile> profiles()
    {
        return Set.of(BrokerProfile.LOW_MEMORY, BrokerProfile.DISK_FULL);
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        return f.profile() == BrokerProfile.LOW_MEMORY ? memory(f) : disk(f);
    }

    private String memory(Fixture f) throws Exception
    {
        String queue = f.name("memory");
        f.requireNew(List.of(queue));
        f.reserve((long) MESSAGES * BODY);
        f.createQueue(queue);
        try (Sender s = f.sender())
        {
            for (int i = 1; i <= MESSAGES; i++)
            {
                s.send(queue, i, s.session().createTextMessage("m".repeat(BODY)), "text", BODY, "",
                        Sender.Options.PERSISTENT);
            }
        }
        f.await(queue, DeliveryScenario.counts("messageCount", MESSAGES));
        long global = number(f.management().attribute(ResourceNames.BROKER, "globalMaxSize"));
        long percent = number(f.management().attribute(ResourceNames.BROKER, "addressMemoryUsagePercentage"));
        String text = "address memory " + percent + "% of a global-max-size of " + global + " bytes after " + MESSAGES
                + " x " + BODY + " bytes";
        return f.assertThat(global == 2_097_152 && percent >= 50, text);
    }

    private String disk(Fixture f) throws Exception
    {
        String queue = f.name("disk");
        f.requireNew(List.of(queue));
        f.createQueue(queue);
        // Disk usage is measured by the broker's periodic disk check (every 5s by default) and reads 0 until the first
        // one has run, so wait for a reading rather than trusting the first.
        double usage = 0;
        long deadline = System.nanoTime() + f.limits().readinessDeadline().toNanos();
        while (usage <= 0 && System.nanoTime() < deadline)
        {
            f.job().checkCancelled();
            usage = ((Number) f.management().attribute(ResourceNames.BROKER, "diskStoreUsage")).doubleValue();
            if (usage <= 0)
            {
                Thread.sleep(500);
            }
        }
        long threshold = number(f.management().attribute(ResourceNames.BROKER, "maxDiskUsage"));
        int accepted = f.sendBlocking(queue, 1, 100, Duration.ofSeconds(5));
        String text = String.format("disk store %.1f%% used against a max-disk-usage of %d%%; a persistent send was %s",
                usage * 100, threshold, accepted == 1 ? "accepted" : "not accepted within 5s");
        return f.assertThat(usage * 100 > threshold, text);
    }

    private static long number(Object value)
    {
        if (value instanceof Number n)
        {
            return n.longValue();
        }
        throw new LabException("Expected a number from the broker, got " + value);
    }
}
