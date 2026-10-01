package com.culberth.tools.artemislab.scenario;

import static com.culberth.tools.artemislab.scenario.DeliveryScenario.counts;
import static com.culberth.tools.artemislab.scenario.DeliveryScenario.settings;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import jakarta.jms.JMSException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Component;

/**
 * {@code PRESSURE} (P01–P03): addresses filled past small limits under each full-address policy, and an operator block.
 * Limits are lowered on these addresses only — 20KB, pages of 10KB — so nothing near the host's disk or the broker's
 * global memory is used. The recipes are the ones Artemis Browser's {@code AddressPressureIT} verified.
 *
 * <ul>
 * <li>{@code page} — PAGE: 40 × 1KB sent, all kept, pages written to disk.</li>
 * <li>{@code fail} — FAIL: sends refused with "address is full" once over the limit; the lab counts what was
 * accepted.</li>
 * <li>{@code drop} — DROP: every send accepted, the excess silently discarded: routed ≫ kept.</li>
 * <li>{@code block} — BLOCK: the sender waits for credit; after 5s the lab gives up and closes it. Overshoots its limit
 * without the paging flag.</li>
 * <li>{@code blocked} — ample room, one message, blocked by an operator through management; steps unblock and block it
 * again.</li>
 * </ul>
 *
 * Global memory and disk thresholds (P04) need a broker started with lower limits; that is a broker profile, not this
 * recipe.
 */
@Component
public class PressureScenario implements Recipe
{

    public static final String ID = "PRESSURE";
    static final int MESSAGES = 40;
    static final int BODY = 1000;
    static final Duration BLOCK_WAIT = Duration.ofSeconds(5);

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public List<Step> steps(LabLimits limits)
    {
        return List.of(new Step("unblock", "Unblock blocked",
                "Lifts the operator's management block on blocked; Browser's observed block should clear.", List.of()),
                new Step("block", "Block blocked", "Blocks blocked through management again.", List.of()));
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        List<String> policies = List.of("page", "fail", "drop", "block");
        List<String> names = new ArrayList<>(policies.stream().map(f::name).toList());
        names.add(f.name("blocked"));
        f.requireNew(names);
        f.reserve(4L * MESSAGES * BODY + BODY);
        for (String policy : policies)
        {
            String address = f.name(policy);
            f.createQueue(address);
            f.addAddressSettings(address, settings("maxSizeBytes", 20000, "pageSizeBytes", 10000,
                    "addressFullMessagePolicy", policy.toUpperCase()));
        }
        f.createQueue(f.name("blocked"));

        List<String> outcomes = new ArrayList<>();
        outcomes.add("page accepted " + fill(f, f.name("page")));
        outcomes.add("fail accepted " + fill(f, f.name("fail")));
        outcomes.add("drop accepted " + fill(f, f.name("drop")));
        outcomes.add("block accepted " + f.sendBlocking(f.name("block"), MESSAGES, BODY, BLOCK_WAIT));
        try (Sender s = f.sender())
        {
            s.send(f.name("blocked"), 1, s.session().createTextMessage("held"), "text", 4,
                    "then blocked by an operator", Sender.Options.PERSISTENT);
        }
        f.management().invoke(ResourceNames.ADDRESS + f.name("blocked"), "block");

        f.await(f.name("page"), counts("messageCount", (long) MESSAGES));
        long pages = number(f.management().attribute(ResourceNames.ADDRESS + f.name("page"), "numberOfPages"));
        long failKept = f.management().messageCount(f.name("fail"));
        long dropKept = f.management().messageCount(f.name("drop"));
        long dropRouted = number(
                f.management().attribute(ResourceNames.ADDRESS + f.name("drop"), "routedMessageCount"));
        long blockKept = f.management().messageCount(f.name("block"));
        boolean held = Boolean.TRUE
                .equals(f.management().attribute(ResourceNames.ADDRESS + f.name("blocked"), "blockedViaManagement"));
        String measured = "page " + pages + " pages; fail kept " + failKept + "; drop kept " + dropKept + " of "
                + dropRouted + " routed; block kept " + blockKept + "; blocked blocked " + held;
        if (pages < 1 || failKept >= MESSAGES || dropKept >= dropRouted || blockKept >= MESSAGES || !held)
        {
            f.record("assert pressure", "FAILED: " + measured);
            throw new LabException("Fixture assertion failed: " + measured);
        }
        f.record("assert pressure", measured);
        return String.join("; ", outcomes) + ". Measured: " + measured + ".";
    }

    @Override
    public String step(Fixture f, String stepId, Map<String, Integer> params) throws Exception
    {
        String held = f.name("blocked");
        f.requireOwned(held, ID);
        String operation = switch (stepId)
        {
            case "unblock", "block" -> stepId;
            default -> throw new LabException(ID + " has no step " + stepId + ".");
        };
        f.management().invoke(ResourceNames.ADDRESS + held, operation);
        Object blocked = f.management().attribute(ResourceNames.ADDRESS + held, "blockedViaManagement");
        String outcome = (operation.equals("block") ? "Blocked " : "Unblocked ") + held + "; blockedViaManagement "
                + blocked + ".";
        f.record(stepId, outcome);
        return outcome;
    }

    /** Sends up to {@link #MESSAGES} 1KB bodies, stopping at the first refusal; returns how many were accepted. */
    private static int fill(Fixture f, String queue) throws Exception
    {
        int accepted = 0;
        try (Sender s = f.sender())
        {
            for (int i = 1; i <= MESSAGES; i++)
            {
                try
                {
                    s.send(queue, i, s.session().createTextMessage("x".repeat(BODY)), "text", BODY, "",
                            Sender.Options.PERSISTENT);
                    accepted++;
                }
                catch (JMSException full)
                {
                    // FAIL answers "address is full": that is the condition being prepared.
                    f.record("refused", queue + " refused send " + i + ": " + full.getMessage());
                    break;
                }
            }
        }
        return accepted;
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
