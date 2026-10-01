package com.culberth.tools.artemislab.scenario;

import static com.culberth.tools.artemislab.scenario.DeliveryScenario.counts;
import static com.culberth.tools.artemislab.scenario.DeliveryScenario.settings;

import com.culberth.tools.artemislab.LabLimits;
import com.culberth.tools.artemislab.worker.HeldConsumer;
import com.culberth.tools.artemislab.worker.OpenClient;
import jakarta.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Component;

/**
 * {@code BEHAVIOR} (Q01–Q04): queues whose configuration explains what their counters show. The recipes are the ones
 * Artemis Browser's {@code QueueBehaviorIT} verified on both broker versions.
 *
 * <ul>
 * <li>Q01 — {@code idle}: 5 messages, no client at all. {@code browsed}: 5 messages and only a browse-only client held
 * open. {@code paused}: 5 messages, paused through management (steps resume and pause it).</li>
 * <li>Q02 — {@code lvq}: last-value key {@code k}, 5 sends with one value, 1 kept. {@code ring}: ring size 3, 10 sent,
 * 3 kept. Neither replacement nor eviction touches a counter.</li>
 * <li>Q03 — {@code keep}: non-destructive, 3 sent and all 3 consumed and acknowledged: still 3, acknowledged 0.
 * {@code purge}: purge on no consumers, no dead-letter address; 3 held by a consumer that then leaves: 0 left, 3
 * killed.</li>
 * <li>Q04 — {@code exclusive}: two buffered consumers, 20 sent, all to one. {@code grouped}: two consumers, groups
 * g1/g2, 10 each. {@code gated}: dispatch waits for 2 consumers and has one; the <em>Add gate consumer</em> step adds
 * the second.</li>
 * </ul>
 */
@Component
public class BehaviorScenario implements Recipe
{

    public static final String ID = "BEHAVIOR";
    static final int BODY = 40;

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public List<Step> steps(LabLimits limits)
    {
        return List.of(
                new Step("resume", "Resume paused", "Resumes paused through management; its 5 become deliverable.",
                        List.of()),
                new Step("pause", "Pause paused", "Pauses paused again through management.", List.of()),
                new Step("gate", "Add gate consumer",
                        "Opens the second consumer gated is waiting for; dispatch then starts.", List.of()));
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        List<String> suffixes = List.of("idle", "browsed", "paused", "lvq", "ring", "keep", "purge", "exclusive",
                "grouped", "gated");
        f.requireNew(suffixes.stream().map(f::name).toList());
        f.reserve(80L * BODY);
        f.workers(7);

        f.createQueue(f.name("idle"));
        f.createQueue(f.name("browsed"));
        f.createQueue(f.name("paused"));
        f.createQueue(f.name("lvq"), Map.of("last-value-key", "k"));
        f.createQueue(f.name("ring"), Map.of("ring-size", 3));
        f.createQueue(f.name("keep"), Map.of("non-destructive", true));
        f.createQueue(f.name("purge"), Map.of("purge-on-no-consumers", true));
        f.createQueue(f.name("exclusive"), Map.of("exclusive", true));
        f.createQueue(f.name("grouped"));
        f.createQueue(f.name("gated"), Map.of("consumers-before-dispatch", 2));
        // No dead-letter address: a message killed on purge then has nowhere to go, which Diagnose explains.
        f.addAddressSettings(f.name("purge"), settings("deadLetterAddress", ""));

        try (Sender s = f.sender())
        {
            send(s, f.name("idle"), 5, null);
            send(s, f.name("browsed"), 5, null);
            send(s, f.name("paused"), 5, null);
            for (int i = 1; i <= 5; i++)
            {
                TextMessage message = text(s, "lvq update " + i);
                message.setStringProperty("k", "same");
                s.send(f.name("lvq"), i, message, "text", BODY, i == 5 ? "the one kept" : "replaced",
                        Sender.Options.PERSISTENT);
            }
            send(s, f.name("ring"), 10, null);
            send(s, f.name("keep"), 3, null);
        }
        f.management().invoke(ResourceNames.QUEUE + f.name("paused"), "pause");
        f.browse("browse-only client on browsed", f.name("browsed"));

        int taken = f.consume(f.name("keep"), 3);

        // Purge: three in flight to a consumer that never acknowledges, then it leaves.
        HeldConsumer leaving = f.hold("leaving consumer on purge", f.clientId("purge"), f.name("purge"), null, 0);
        try (Sender s = f.sender())
        {
            send(s, f.name("purge"), 3, "killed when its last consumer leaves");
        }
        leaving.hold(3, f.limits().operationTimeout().toMillis());
        f.record("worker", leaving.stop("left with 3 unacknowledged"));

        // Consumers that never receive: on the default window the broker dispatches into their buffers.
        for (String who : List.of("first", "second"))
        {
            f.openClient(who + " consumer on exclusive", f.clientId("exclusive-" + who), f.name("exclusive"),
                    OpenClient.Role.CONSUMER, 1, true);
            f.openClient(who + " consumer on grouped", f.clientId("grouped-" + who), f.name("grouped"),
                    OpenClient.Role.CONSUMER, 1, true);
        }
        f.openClient("first consumer on gated", f.clientId("gated-first"), f.name("gated"), OpenClient.Role.CONSUMER, 1,
                true);
        try (Sender s = f.sender())
        {
            send(s, f.name("exclusive"), 20, null);
            for (int i = 1; i <= 20; i++)
            {
                TextMessage message = text(s, "grouped " + i);
                message.setStringProperty("JMSXGroupID", i % 2 == 0 ? "g1" : "g2");
                s.send(f.name("grouped"), i, message, "text", BODY, i % 2 == 0 ? "group g1" : "group g2",
                        Sender.Options.PERSISTENT);
            }
            send(s, f.name("gated"), 3, "waits for a second consumer");
        }

        List<String> asserted = new ArrayList<>();
        asserted.add(f.await(f.name("idle"), counts("messageCount", 5, "consumerCount", 0)));
        asserted.add(f.await(f.name("browsed"), counts("messageCount", 5, "deliveringCount", 0)));
        asserted.add(f.await(f.name("paused"), counts("messageCount", 5)));
        asserted.add(f.await(f.name("lvq"), counts("messageCount", 1, "messagesAdded", 5)));
        asserted.add(f.await(f.name("ring"), counts("messageCount", 3, "messagesAdded", 10)));
        asserted.add(f.await(f.name("keep"), counts("messageCount", 3, "messagesAcknowledged", 0)));
        asserted.add(f.await(f.name("purge"), counts("messageCount", 0, "messagesKilled", 3)));
        asserted.add(f.await(f.name("exclusive"), counts("deliveringCount", 20, "consumerCount", 2)));
        asserted.add(f.await(f.name("grouped"), counts("deliveringCount", 20, "consumerCount", 2, "groupCount", 2)));
        asserted.add(f.await(f.name("gated"), counts("messageCount", 3, "deliveringCount", 0, "consumerCount", 1)));
        if (!Boolean.TRUE.equals(f.management().attribute(ResourceNames.QUEUE + f.name("paused"), "paused")))
        {
            throw new IllegalStateException(f.name("paused") + " is not paused");
        }
        return "Asserted " + asserted.size() + " queues (and paused is paused); took " + taken
                + " from keep, which still holds 3. Clients are open (see Workers).";
    }

    @Override
    public String step(Fixture f, String stepId, Map<String, Integer> params) throws Exception
    {
        String paused = f.name("paused");
        String gated = f.name("gated");
        f.requireOwned(paused, ID);
        String outcome = switch (stepId)
        {
            case "resume" ->
            {
                f.management().invoke(ResourceNames.QUEUE + paused, "resume");
                yield "Resumed " + paused + ".";
            }
            case "pause" ->
            {
                f.management().invoke(ResourceNames.QUEUE + paused, "pause");
                yield "Paused " + paused + ".";
            }
            case "gate" ->
            {
                f.openClient("second consumer on gated", f.clientId("gated-second"), gated, OpenClient.Role.CONSUMER, 1,
                        true);
                yield f.await(gated, counts("deliveringCount", 3, "consumerCount", 2));
            }
            default -> Recipe.super.step(f, stepId, params);
        };
        f.record(stepId, outcome);
        return outcome;
    }

    private static void send(Sender s, String queue, int count, String note) throws Exception
    {
        for (int i = 1; i <= count; i++)
        {
            s.send(queue, i, text(s, queue + " " + i), "text", BODY, note, Sender.Options.PERSISTENT);
        }
    }

    private static TextMessage text(Sender s, String head) throws jakarta.jms.JMSException
    {
        return s.session().createTextMessage(BasicScenario.pad(head, BODY));
    }
}
