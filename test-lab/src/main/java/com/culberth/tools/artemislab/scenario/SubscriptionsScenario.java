package com.culberth.tools.artemislab.scenario;

import static com.culberth.tools.artemislab.scenario.DeliveryScenario.counts;
import static com.culberth.tools.artemislab.scenario.DeliveryScenario.settings;

import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code SUBSCRIPTIONS} (A01–A03): routing where a queue's name is not its address's.
 *
 * <ul>
 * <li>A01 — anycast queue {@code orders-q} on address {@code orders}; multicast address {@code feed} with queues
 * {@code feed-sub-a} and {@code feed-sub-b}. Six messages each: to {@code orders::orders-q} by FQQN, and published to
 * {@code feed}.</li>
 * <li>A02 — multicast address {@code events}: durable {@code lab-<run>.durable-all} (left offline), durable filtered
 * {@code lab-<run>.durable-eu} on {@code region = 'eu'} (drained), shared durable {@code lab-<run>-shared}, and a live
 * non-durable subscriber kept as a worker. Ten messages alternating eu/us.</li>
 * <li>A03 — address {@code routed} with an exclusive divert of {@code region = 'eu'} to {@code routed.eu} and a
 * non-exclusive divert of everything to {@code routed.audit}; six messages alternating eu/us. And {@code nowhere}, a
 * multicast address with no queue and auto-creation off: three messages, all unrouted.</li>
 * </ul>
 *
 * Durable subscriptions use client id {@code lab-<run>}, so their queues are {@code lab-<run>.durable-all} and
 * {@code lab-<run>.durable-eu}; the shared one is {@code lab-<run>-shared}. Not the {@code lab.<run>.} prefix: Artemis
 * escapes every dot inside a client id or subscription name when it names the queue ({@code lab\.r…}), so a dotted
 * client id would give a queue name nobody would type. Owned by exact name all the same.
 */
@Component
public class SubscriptionsScenario implements Recipe
{

    public static final String ID = "SUBSCRIPTIONS";
    static final int BODY = 60;

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        String orders = f.name("orders");
        String ordersQueue = f.name("orders-q");
        String feed = f.name("feed");
        String events = f.name("events");
        String routed = f.name("routed");
        String nowhere = f.name("nowhere");
        String subscriber = "lab-" + f.runId();
        String durableAll = subscriber + ".durable-all";
        String durableEu = subscriber + ".durable-eu";
        String shared = subscriber + "-shared";
        f.requireNew(List.of(orders, ordersQueue, feed, events, routed, nowhere, durableAll, durableEu, shared));
        f.reserve((6L + 6 + 10 + 6 + 3) * BODY);
        f.workers(1);

        // A01
        f.createQueue(ordersQueue, orders, "ANYCAST");
        f.createAddress(feed, "MULTICAST");
        f.createQueue(f.name("feed-sub-a"), feed, "MULTICAST");
        f.createQueue(f.name("feed-sub-b"), feed, "MULTICAST");

        // A02: the subscriptions must exist before anything is published, or the publication has nowhere to go.
        f.createAddress(events, "MULTICAST");
        try (Connection connection = f.connection(subscriber))
        {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(events);
            f.createSubscriptionQueue(durableAll, events,
                    () -> session.createDurableSubscriber(topic, "durable-all").close());
            f.createSubscriptionQueue(durableEu, events,
                    () -> session.createDurableSubscriber(topic, "durable-eu", "region = 'eu'", false).close());
            session.close();
        }
        try (Connection anonymous = f.connection(null))
        {
            Session session = anonymous.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(events);
            f.createSubscriptionQueue(shared, events, () -> session.createSharedDurableConsumer(topic, shared).close());
            session.close();
        }
        f.subscribeLive("live non-durable subscriber on events", f.clientId("live"), events, null);

        // A03
        f.createQueue(routed);
        f.createQueue(f.name("routed.eu"));
        f.createQueue(f.name("routed.audit"));
        f.createDivert(f.name("div-eu"), routed, f.name("routed.eu"), true, "region = 'eu'", "PASS");
        f.createDivert(f.name("div-audit"), routed, f.name("routed.audit"), false, null, "PASS");
        f.createAddress(nowhere, "MULTICAST");
        f.addAddressSettings(nowhere, settings("autoCreateQueues", false, "autoCreateAddresses", false));

        try (Sender s = f.sender())
        {
            for (int i = 1; i <= 6; i++)
            {
                // By FQQN: a JMS send to plain "orders" makes the client auto-create a queue of that name on the
                // address, and anycast then splits the messages between it and orders-q.
                s.send(orders + "::" + ordersQueue, i, text(s, "orders " + i), "text", BODY, "to orders-q by FQQN",
                        Sender.Options.PERSISTENT);
                s.publish(feed, i, text(s, "feed " + i), "text", BODY, "to both feed queues",
                        Sender.Options.PERSISTENT);
                TextMessage diverted = text(s, "routed " + i);
                diverted.setStringProperty("region", i % 2 == 1 ? "eu" : "us");
                s.send(routed, i, diverted, "text", BODY, i % 2 == 1 ? "eu: diverted exclusively" : "us",
                        Sender.Options.PERSISTENT);
            }
            for (int i = 1; i <= 10; i++)
            {
                TextMessage message = text(s, "events " + i);
                message.setStringProperty("region", i % 2 == 1 ? "eu" : "us");
                s.publish(events, i, message, "text", BODY, i % 2 == 1 ? "region=eu" : "region=us",
                        Sender.Options.PERSISTENT);
            }
            for (int i = 1; i <= 3; i++)
            {
                s.publish(nowhere, i, text(s, "nowhere " + i), "text", BODY, "unrouted", Sender.Options.PERSISTENT);
            }
        }

        int drained = drain(f, subscriber, events);

        List<String> asserted = new ArrayList<>();
        asserted.add(f.await(ordersQueue, counts("messageCount", 6)));
        asserted.add(f.await(f.name("feed-sub-a"), counts("messageCount", 6)));
        asserted.add(f.await(f.name("feed-sub-b"), counts("messageCount", 6)));
        asserted.add(f.await(durableAll, counts("messageCount", 10, "consumerCount", 0)));
        asserted.add(f.await(durableEu, counts("messageCount", 0, "messagesAdded", 5, "messagesAcknowledged", 5)));
        asserted.add(f.await(shared, counts("messageCount", 10)));
        asserted.add(f.await(routed, counts("messageCount", 3)));
        asserted.add(f.await(f.name("routed.eu"), counts("messageCount", 3)));
        asserted.add(f.await(f.name("routed.audit"), counts("messageCount", 3)));
        asserted.add(f.awaitAddress(nowhere, counts("unRoutedMessageCount", 3)));
        return "Asserted " + asserted.size() + " conditions; drained " + drained
                + " from durable-eu; a live subscriber is open (see Workers).";
    }

    /** Receives and acknowledges everything on the filtered durable subscription, then leaves it offline. */
    private static int drain(Fixture f, String clientId, String events) throws Exception
    {
        try (Connection connection = f.connection(clientId))
        {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            try (MessageConsumer consumer = session.createDurableSubscriber(session.createTopic(events), "durable-eu",
                    "region = 'eu'", false))
            {
                int drained = 0;
                while (consumer.receive(2000) != null)
                {
                    drained++;
                }
                return drained;
            }
            finally
            {
                session.close();
            }
        }
    }

    private static TextMessage text(Sender s, String head) throws jakarta.jms.JMSException
    {
        return s.session().createTextMessage(BasicScenario.pad(head, BODY));
    }
}
