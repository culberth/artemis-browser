package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.culberth.tools.artemisbrowser.broker.Subscription.Kind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading an address's subscriptions and who is attached to them.
 *
 * <p>
 * The JSON here is what a 2.44.0 broker actually answered for an {@code events} address carrying one of each kind of
 * subscription — see {@code .claude/memory.md} — trimmed to the fields read. The naming rules the kinds come from are
 * the broker's, and a fixture invented from the docs would only test that this code agrees with itself.
 */
class SubscriptionDirectoryTest
{

    private static final String EVENTS_QUEUES = """
        {"data":[
        {"name":"91d0cace-7a97-4656-a60d-a2495a369035","address":"events","filter":"region = 'us'","durable":"false",
         "temporary":"true","consumerCount":"1","user":"artemis","routingType":"MULTICAST","messagesAdded":"0",
         "messageCount":"0","messagesAcked":"0","deliveringCount":"0","exclusive":"false"},
        {"name":"nonDurable.probe-live.shared-nd","address":"events","filter":"","durable":"false","temporary":"true",
         "consumerCount":"1","user":"artemis","routingType":"MULTICAST","messageCount":"0"},
        {"name":"probe-a.sub-a","address":"events","filter":"region = 'eu' AND AMQPriority > 3","durable":"true",
         "temporary":"false","consumerCount":"0","user":"artemis","routingType":"MULTICAST","messagesAdded":"1",
         "messageCount":"1","messagesAcked":"0","messagesExpired":"0","messagesKilled":"0"},
        {"name":"probe-b.sub-b","address":"events","filter":"","durable":"true","temporary":"false",
         "consumerCount":"0","user":"artemis","routingType":"MULTICAST","messagesAdded":"6","messageCount":"6"},
        {"name":"shared-c","address":"events","filter":"","durable":"true","temporary":"false","consumerCount":"0",
         "user":"artemis","routingType":"MULTICAST","messagesAdded":"6","messageCount":"6"}
        ],"count":5}""";

    private static final String CONSUMERS = """
        {"data":[
        {"id":"21","clientID":"","user":"artemis","protocol":"CORE","queue":"orders","filter":"",
         "remoteAddress":"172.17.0.1:46928","messagesDelivered":"17","messagesAcknowledged":"17",
         "creationTime":"Sun Sep 27 20:20:41 GMT 2026","lastDeliveredTime":1790540442859},
        {"id":"113","clientID":"probe-live","user":"artemis","protocol":"CORE",
         "queue":"91d0cace-7a97-4656-a60d-a2495a369035","filter":"region = 'us'","address":"events",
         "remoteAddress":"172.17.0.1:46984","messagesDelivered":"0","messagesAcknowledged":"0"},
        {"id":"117","clientID":"probe-live","user":"artemis","protocol":"CORE",
         "queue":"nonDurable.probe-live.shared-nd","filter":"","address":"events",
         "remoteAddress":"172.17.0.1:46984","messagesDelivered":"4","messagesAcknowledged":"3"}
        ],"count":3}""";

    private BrokerSession brokerSession;
    private ManagementChannel management;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
        given(management.replyQueueName()).willReturn("tmp-reply-9f2c");
    }

    @Test
    @DisplayName("each kind of subscription is told apart by the broker's own naming and flags")
    void classifiesEachKind()
    {
        Map<String, Subscription> byName = eventsSubscriptions();

        assertEquals(Kind.NON_DURABLE, byName.get("91d0cace-7a97-4656-a60d-a2495a369035").kind());
        assertEquals(Kind.SHARED_NON_DURABLE, byName.get("nonDurable.probe-live.shared-nd").kind());
        assertEquals(Kind.DURABLE, byName.get("probe-a.sub-a").kind());
        assertEquals(Kind.DURABLE, byName.get("shared-c").kind());
        assertEquals(Kind.QUEUE, Subscription.classify("orders", "ANYCAST", true, false));
    }

    @Test
    @DisplayName("client id and subscription name are split from the queue name, prefix and all")
    void splitsTheNameIntoHints()
    {
        Map<String, Subscription> byName = eventsSubscriptions();

        assertEquals("probe-a", byName.get("probe-a.sub-a").clientIdHint());
        assertEquals("sub-a", byName.get("probe-a.sub-a").subscriptionHint());
        assertEquals("probe-live", byName.get("nonDurable.probe-live.shared-nd").clientIdHint());
        assertEquals("shared-nd", byName.get("nonDurable.probe-live.shared-nd").subscriptionHint());
        // A shared durable subscription made without a client id is named after the subscription alone.
        assertNull(byName.get("shared-c").clientIdHint());
        assertEquals("shared-c", byName.get("shared-c").subscriptionHint());
        // A plain non-durable subscription is a UUID; there is nothing to split.
        assertNull(byName.get("91d0cace-7a97-4656-a60d-a2495a369035").subscriptionHint());
    }

    @Test
    @DisplayName("the filter is carried as the broker stored it — core syntax, empty meaning everything")
    void carriesTheFilter()
    {
        Map<String, Subscription> byName = eventsSubscriptions();

        assertEquals("region = 'eu' AND AMQPriority > 3", byName.get("probe-a.sub-a").filter());
        assertTrue(byName.get("probe-a.sub-a").filtered());
        assertFalse(byName.get("probe-b.sub-b").filtered());
    }

    @Test
    @DisplayName("quoted counters are read, and a durable subscription with messages and nobody attached is stalled")
    void readsCountersAndStall()
    {
        Subscription subB = eventsSubscriptions().get("probe-b.sub-b");

        assertEquals(6, subB.messageCount());
        assertEquals(6, subB.messagesAdded());
        assertTrue(subB.stalled());
        assertEquals("events::probe-b.sub-b", subB.browseName());
    }

    @Test
    @DisplayName("the broker is asked for the one address, with the name escaped into the filter document")
    void asksForTheAddressOnly()
    {
        given(management.invoke(eq(ResourceNames.BROKER), eq("listQueues"), anyString(), anyInt(), anyInt()))
                .willReturn("{\"data\":[],\"count\":0}");

        new QueueDirectory(brokerSession).onAddress("we\"ird");

        verify(management).invoke(ResourceNames.BROKER, "listQueues",
                "{\"field\":\"address\",\"operation\":\"EQUALS\",\"value\":\"we\\\"ird\"}", 1, 200);
    }

    @Test
    @DisplayName("a queue on another address is dropped even if the broker's match was looser than asked")
    void dropsQueuesOnOtherAddresses()
    {
        given(management.invoke(eq(ResourceNames.BROKER), eq("listQueues"), anyString(), anyInt(), anyInt()))
                .willReturn("{\"data\":[{\"name\":\"a\",\"address\":\"events\"},"
                        + "{\"name\":\"b\",\"address\":\"events.eu\"}],\"count\":2}");

        List<Subscription> subscriptions = new QueueDirectory(brokerSession).onAddress("events");

        assertEquals(List.of("a"), subscriptions.stream().map(Subscription::name).toList());
    }

    @Test
    @DisplayName("consumers come from listConsumers, which is the listing that carries the client id")
    void readsConsumersWithClientIds()
    {
        given(management.invoke(eq(ResourceNames.BROKER), eq("listConsumers"), anyString(), eq(1), eq(200)))
                .willReturn(CONSUMERS);

        List<SubscriberConsumer> consumers = new BrokerInfoService(brokerSession)
                .consumersOn(Set.of("91d0cace-7a97-4656-a60d-a2495a369035", "nonDurable.probe-live.shared-nd"));

        assertEquals(2, consumers.size(), "the consumer on 'orders' is not on this address");
        SubscriberConsumer shared = consumers.get(1);
        assertEquals("probe-live", shared.clientId());
        assertEquals("172.17.0.1:46984", shared.remoteAddress());
        assertEquals(4, shared.messagesDelivered());
        assertEquals(3, shared.messagesAcknowledged());
        assertEquals("region = 'us'", consumers.get(0).filter());
    }

    @Test
    @DisplayName("an attached consumer with the same client id confirms the split; without one it stays a guess")
    void confirmsIdentityFromConsumers()
    {
        Map<String, Subscription> byName = eventsSubscriptions();
        SubscriberConsumer live = new SubscriberConsumer("117", "nonDurable.probe-live.shared-nd", "probe-live",
                "artemis", "", "CORE", "", 0, 0);
        AddressDetail detail = new AddressDetail(null, List.copyOf(byName.values()), List.of(live), List.of());

        assertTrue(detail.identityConfirmed(byName.get("nonDurable.probe-live.shared-nd")));
        assertFalse(detail.identityConfirmed(byName.get("probe-a.sub-a")), "nobody is attached to confirm it");
    }

    private Map<String, Subscription> eventsSubscriptions()
    {
        given(management.invoke(eq(ResourceNames.BROKER), eq("listQueues"), anyString(), eq(1), eq(200)))
                .willReturn(EVENTS_QUEUES);
        return new QueueDirectory(brokerSession).onAddress("events").stream()
                .collect(Collectors.toMap(Subscription::name, Function.identity()));
    }
}
