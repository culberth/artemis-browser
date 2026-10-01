package com.culberth.tools.artemislab.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code DELIVERY} (D01–D07): messages that are scheduled, held, redelivered, dead-lettered and expired. The held ones
 * are kept by consumers that outlive the job — workers on the run page, where they can be acknowledged or released.
 *
 * <ul>
 * <li>{@code sched} — 5 waiting and 3 scheduled five minutes out (D01).</li>
 * <li>{@code held} — 20 messages; consumer {@code lab-<run>-holder} holds 10 unacknowledged (D02).</li>
 * <li>{@code all-held} — 250 messages, all held by {@code lab-<run>-all}: browse is empty, the queue is not (D03).</li>
 * <li>{@code imbalance} — 20 messages; {@code lab-<run>-busy} holds 10, {@code lab-<run>-idle} is open and holds none
 * (D04). The age finding needs a held message over ten minutes old: leave it.</li>
 * <li>{@code redelivery} — 3 messages, at most 3 delivery attempts, dead-lettered to {@code redelivery.dlq}; the
 * <em>Roll back once</em> step fails one delivery at a time (D05).</li>
 * <li>{@code expiry} — 4 messages with a 2s TTL and 2 without, expiring to {@code expiry.exp} on the broker's scan
 * (D06).</li>
 * <li>{@code kill-none}, {@code kill-absent}, {@code kill-noqueue} — one message each rolled back to the limit of 2,
 * with no dead-letter address, one that does not exist, and an address with no queue; {@code expire-none} — one message
 * expiring with no expiry address. All four lose their message, counted only as killed or expired (D07).</li>
 * </ul>
 *
 * Address settings are added for exactly these addresses, with auto-creation off where D07 needs it, and removed in
 * cleanup.
 */
@Component
public class DeliveryScenario implements Recipe
{

    public static final String ID = "DELIVERY";
    static final int BODY = 60;
    static final Duration EXPIRY_DEADLINE = Duration.ofSeconds(90);
    static final long TTL = 2000;

    static List<String> queues()
    {
        return List.of("sched", "held", "all-held", "imbalance", "redelivery", "redelivery.dlq", "expiry", "expiry.exp",
                "kill-none", "kill-absent", "kill-noqueue", "expire-none");
    }

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public List<Step> steps(com.culberth.tools.artemislab.LabLimits limits)
    {
        return List.of(new Step("rollback", "Roll back once",
                "Receives the next message on redelivery in a transaction and rolls it back: one failed delivery "
                        + "attempt. The third moves it to redelivery.dlq.",
                List.of()));
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        List<String> names = new ArrayList<>(queues().stream().map(f::name).toList());
        names.add(f.name("dla-noqueue"));
        f.requireNew(names);
        f.reserve((8L + 20 + 250 + 20 + 3 + 6 + 4) * BODY);
        f.workers(4);

        for (String suffix : List.of("sched", "held", "all-held", "imbalance", "redelivery.dlq", "redelivery",
                "expiry.exp", "expiry", "kill-none", "kill-absent", "kill-noqueue", "expire-none"))
        {
            f.createQueue(f.name(suffix));
        }
        f.createAddress(f.name("dla-noqueue"), "ANYCAST");

        f.addAddressSettings(f.name("redelivery"), settings("maxDeliveryAttempts", 3, "deadLetterAddress",
                f.name("redelivery.dlq"), "redeliveryDelay", 0));
        f.addAddressSettings(f.name("expiry"), settings("expiryAddress", f.name("expiry.exp")));
        f.addAddressSettings(f.name("kill-none"), killing(""));
        f.addAddressSettings(f.name("kill-absent"), killing(f.name("missing-dla")));
        f.addAddressSettings(f.name("kill-noqueue"), killing(f.name("dla-noqueue")));
        f.addAddressSettings(f.name("expire-none"),
                settings("expiryAddress", "", "autoCreateQueues", false, "autoCreateAddresses", false));

        try (Sender s = f.sender())
        {
            send(s, f.name("sched"), 5, "waiting", Sender.Options.PERSISTENT);
            for (int i = 6; i <= 8; i++)
            {
                s.send(f.name("sched"), i, s.session().createTextMessage(BasicScenario.pad("sched " + i, BODY)), "text",
                        BODY, "scheduled five minutes out",
                        Sender.Options.PERSISTENT.withDeliveryDelay(Duration.ofMinutes(5).toMillis()));
            }
            send(s, f.name("held"), 20, "", Sender.Options.PERSISTENT);
            send(s, f.name("all-held"), 250, "", Sender.Options.PERSISTENT);
            send(s, f.name("imbalance"), 20, "", Sender.Options.PERSISTENT);
            send(s, f.name("redelivery"), 3, "rolled back by step", Sender.Options.PERSISTENT);
            send(s, f.name("expiry"), 4, "expires after 2s", Sender.Options.PERSISTENT.withTimeToLive(TTL));
            for (int i = 5; i <= 6; i++)
            {
                s.send(f.name("expiry"), i, s.session().createTextMessage(BasicScenario.pad("expiry " + i, BODY)),
                        "text", BODY, "no TTL", Sender.Options.PERSISTENT);
            }
            for (String suffix : List.of("kill-none", "kill-absent", "kill-noqueue"))
            {
                send(s, f.name(suffix), 1, "rolled back to the limit", Sender.Options.PERSISTENT);
            }
            send(s, f.name("expire-none"), 1, "expires after 2s", Sender.Options.PERSISTENT.withTimeToLive(TTL));
        }

        f.hold("holder of 10 on held", f.clientId("holder"), f.name("held"), null, 10);
        f.hold("holder of all 250 on all-held", f.clientId("all"), f.name("all-held"), null, 250);
        f.hold("busy consumer on imbalance", f.clientId("busy"), f.name("imbalance"), null, 10);
        f.hold("idle consumer on imbalance", f.clientId("idle"), f.name("imbalance"), null, 0);

        for (String suffix : List.of("kill-none", "kill-absent", "kill-noqueue"))
        {
            f.rollbackOnce(f.name(suffix));
            f.rollbackOnce(f.name(suffix));
        }

        List<String> asserted = new ArrayList<>();
        asserted.add(f.await(f.name("sched"), counts("messageCount", 8, "scheduledCount", 3)));
        asserted.add(f.await(f.name("held"), counts("messageCount", 20, "deliveringCount", 10)));
        asserted.add(f.await(f.name("all-held"), counts("messageCount", 250, "deliveringCount", 250)));
        asserted.add(f.await(f.name("imbalance"), counts("deliveringCount", 10, "consumerCount", 2)));
        asserted.add(f.await(f.name("redelivery"), counts("messageCount", 3)));
        for (String suffix : List.of("kill-none", "kill-absent", "kill-noqueue"))
        {
            asserted.add(f.await(f.name(suffix), counts("messageCount", 0, "messagesKilled", 1)));
        }
        asserted.add(f.await(f.name("expiry"), counts("messageCount", 2, "messagesExpired", 4), EXPIRY_DEADLINE));
        asserted.add(f.await(f.name("expiry.exp"), counts("messageCount", 4), EXPIRY_DEADLINE));
        asserted.add(f.await(f.name("expire-none"), counts("messageCount", 0, "messagesExpired", 1), EXPIRY_DEADLINE));
        return "Asserted " + asserted.size() + " conditions; 4 consumers are holding messages (see Workers).";
    }

    @Override
    public String step(Fixture f, String stepId, Map<String, Integer> params) throws Exception
    {
        String queue = f.name("redelivery");
        f.requireOwned(queue, ID);
        int attempt = f.rollbackOnce(queue);
        if (attempt == 0)
        {
            return "Nothing left to roll back on " + queue + ".";
        }
        long left = f.management().messageCount(queue);
        long dead = f.management().messageCount(f.name("redelivery.dlq"));
        String outcome = "Rolled back delivery attempt " + attempt + ". redelivery now " + left + ", redelivery.dlq "
                + dead + ".";
        f.record("roll back once", outcome);
        return outcome;
    }

    private static void send(Sender s, String queue, int count, String note, Sender.Options options) throws Exception
    {
        for (int i = 1; i <= count; i++)
        {
            s.send(queue, i, s.session().createTextMessage(BasicScenario.pad(queue + " " + i, BODY)), "text", BODY,
                    note, options);
        }
    }

    private static Map<String, Object> killing(String deadLetterAddress)
    {
        return settings("maxDeliveryAttempts", 2, "deadLetterAddress", deadLetterAddress, "redeliveryDelay", 0,
                "autoCreateQueues", false, "autoCreateAddresses", false);
    }

    static Map<String, Object> settings(Object... pairs)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static Map<String, Long> counts(Object... pairs)
    {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return map;
    }
}
