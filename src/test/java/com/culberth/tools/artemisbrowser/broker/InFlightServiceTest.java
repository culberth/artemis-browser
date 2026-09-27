package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Set;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code listDeliveringMessagesAsJSON} names each consumer only by a {@code toString()}, and returns every in-flight
 * message with no paging. The fixtures are replies from a 2.44.0 broker, cut down: a CORE consumer, and an OpenWire one
 * whose session ID has colons in it — the case a split on ':' gets silently wrong.
 */
class InFlightServiceTest
{

    private static final String CORE_CONSUMER = "ServerConsumer [id=800977e2:18098baf-babe-11f1-8646-00155d348692:0, "
            + "filter=null, binding=LocalQueueBinding [address=work, queue=QueueImpl[name=work, "
            + "postOffice=PostOfficeImpl [server=ActiveMQServerImpl::name=0.0.0.0], temp=false]@157a75ce]]";

    private static final String OPENWIRE_CONSUMER = "ServerConsumer [id=d46364be:ID:DESKTOP-LBV5B35-63679-1790546223121"
            + "-1:1:1:0, filter=null, binding=LocalQueueBinding [address=work, queue=QueueImpl[name=work, "
            + "postOffice=PostOfficeImpl [server=ActiveMQServerImpl::name=0.0.0.0], temp=false]@76eedc02]]";

    private static final String CORE_ELEMENT = """
        {"durable":false,"address":"work","__AMQ_CID":"2894f862","_AMQ_ROUTING_TYPE":1,"messageID":30,
         "expiration":0,"region":"eu","type":3,"priority":4,"userID":"ID:28a5c146","n":0,
         "timestamp":1790546131370}""";

    private static final String OPENWIRE_ELEMENT = """
        {"_AMQ_GROUP_SEQUENCE":0,"address":"work","messageID":126370,"__HDR_PRODUCER_ID":"ID:DESKTOP-1:1:1:1",
         "type":3,"priority":4,"userID":"ID:5f6ce32b","n":0,"__HDR_COMMAND_ID":5,"durable":true,
         "__HDR_ARRIVAL":0,"__AMQ_CID":"openwire-holder","_AMQ_ROUTING_TYPE":1,
         "__HDR_MESSAGE_ID":"ID:DESKTOP-1:1:1:1:1","expiration":0,"__HDR_DROPPABLE":false,
         "__HDR_BROKER_IN_TIME":1790546223269,"timestamp":1790546223265}""";

    private BrokerSession brokerSession;
    private ManagementChannel management;
    private BrokerInfoService brokerInfo;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
        brokerInfo = mock(BrokerInfoService.class);
        given(brokerInfo.consumers()).willReturn(List.of());
        given(brokerInfo.consumersOn(Set.of("work"))).willReturn(List.of());
    }

    @Test
    @DisplayName("messages are grouped by consumer, with headers split from properties")
    void readsCoreConsumer()
    {
        reply("[" + entry(CORE_CONSUMER, CORE_ELEMENT) + "]");

        InFlight inFlight = service(5000).inFlight(stats(1));

        assertFalse(inFlight.notRead());
        assertEquals(1, inFlight.listedCount());
        InFlightConsumer consumer = inFlight.consumers().get(0);
        assertEquals("800977e2", consumer.connectionId());
        assertEquals("18098baf-babe-11f1-8646-00155d348692", consumer.sessionId());
        assertEquals("0", consumer.consumerId());

        InFlightMessage message = consumer.messages().get(0);
        assertEquals("ID:28a5c146", message.messageId());
        assertEquals(30L, message.coreId());
        assertEquals("Text", message.type());
        assertEquals(1790546131370L, message.timestamp());
        assertEquals(List.of("region", "n"), List.copyOf(message.properties().keySet()),
                "headers and Artemis's own bookkeeping are not properties");
    }

    @Test
    @DisplayName("an OpenWire session ID has colons in it, and still parses to the ids the consumer listing reports")
    void readsOpenWireConsumer()
    {
        reply("[" + entry(OPENWIRE_CONSUMER, OPENWIRE_ELEMENT) + "]");

        InFlightConsumer consumer = service(5000).inFlight(stats(1)).consumers().get(0);

        assertEquals("d46364be", consumer.connectionId());
        assertEquals("ID:DESKTOP-LBV5B35-63679-1790546223121-1:1:1", consumer.sessionId());
        assertEquals("0", consumer.consumerId());
        assertEquals("d46364be:ID:DESKTOP-LBV5B35-63679-1790546223121-1:1:1:0", consumer.key());
        assertEquals(List.of("_AMQ_GROUP_SEQUENCE", "n"), List.copyOf(consumer.messages().get(0).properties().keySet()),
                "OpenWire's headers arrive as __HDR_ properties and are not the producer's");
    }

    @Test
    @DisplayName("consumer text that does not have the expected shape is kept as it came, not guessed at")
    void keepsUnparsableConsumerText()
    {
        InFlightConsumer consumer = InFlightService.consumer("SomethingElse [name=mystery]", List.of());

        assertFalse(consumer.identified());
        assertNull(consumer.key());
        assertEquals("SomethingElse [name=mystery]", consumer.consumerName());
        assertFalse(InFlightService.consumer("ServerConsumer [id=no-colons, filter=null]", List.of()).identified());
    }

    @Test
    @DisplayName("over the limit, the broker is not asked at all — the reply has no paging to make it smaller")
    void doesNotAskAboveTheLimit()
    {
        InFlight inFlight = service(100).inFlight(stats(101));

        assertTrue(inFlight.notRead());
        assertTrue(inFlight.consumers().isEmpty());
        assertEquals(101, inFlight.deliveringCount());
        verifyNoInteractions(management);
    }

    @Test
    @DisplayName("each consumer is tied to its client through both listings, and holds the broker's own count")
    void matchesConsumersToClients()
    {
        reply("[" + entry(OPENWIRE_CONSUMER, OPENWIRE_ELEMENT) + "," + entry("Unrecognised [x]", CORE_ELEMENT) + "]");
        given(brokerInfo.consumers()).willReturn(List.of(
                new BrokerConsumer("0", "work", "d46364be", "ID:DESKTOP-LBV5B35-63679-1790546223121-1:1:1", "126380",
                        false, 10, 10, 0, "OK", false),
                // Same consumer number on another queue: must not match.
                new BrokerConsumer("0", "other", "d46364be", "ID:DESKTOP-LBV5B35-63679-1790546223121-1:1:1", "9", false,
                        1, 1, 0, "OK", false)));
        given(brokerInfo.consumersOn(Set.of("work"))).willReturn(List.of(new SubscriberConsumer("126380", "work",
                "openwire-holder", "artemis", "172.17.0.1:44324", "OPENWIRE", "", 10, 0)));

        InFlight inFlight = service(5000).inFlight(stats(2));

        InFlightConsumer matched = inFlight.consumers().get(0);
        assertEquals("openwire-holder", matched.client().clientId());
        assertEquals(10L, matched.inTransit());
        assertEquals(10L, matched.holding(), "the broker's count, not the one message listed");
        assertEquals(9L, matched.notShown());

        InFlightConsumer unmatched = inFlight.consumers().get(1);
        assertNull(unmatched.client());
        assertEquals(1L, unmatched.holding());
    }

    @Test
    @DisplayName("over the limit, the consumers holding the messages are still named, from the listings alone")
    void namesHoldersAboveTheLimit()
    {
        given(brokerInfo.consumers()).willReturn(
                List.of(new BrokerConsumer("0", "work", "conn-a", "sess-a", "7", false, 150, 150, 0, "OK", false),
                        new BrokerConsumer("1", "work", "conn-b", "sess-b", "8", false, 0, 4, 4, "OK", false)));
        given(brokerInfo.consumersOn(Set.of("work"))).willReturn(
                List.of(new SubscriberConsumer("7", "work", "hoarder", "artemis", "10.0.0.1:1", "CORE", "", 150, 0)));

        InFlight inFlight = service(100).inFlight(stats(150));

        assertTrue(inFlight.notRead());
        assertEquals(1, inFlight.consumers().size(), "a consumer holding nothing is not a holder");
        assertEquals("hoarder", inFlight.consumers().get(0).client().clientId());
        assertEquals(150L, inFlight.consumers().get(0).holding());
        verifyNoInteractions(management);
    }

    @Test
    @DisplayName("an in-flight message's age is measured from when it was sent")
    void agesFromSendTime()
    {
        InFlight inFlight = service(5000).parse("work", 1, "[" + entry(CORE_CONSUMER, CORE_ELEMENT) + "]",
                1790546131370L + 125_000L);

        assertEquals("2m 5s", inFlight.consumers().get(0).messages().get(0).ageText());
    }

    @Test
    @DisplayName("nothing delivering means nothing to ask")
    void doesNotAskWhenNothingIsInFlight()
    {
        InFlight inFlight = service(5000).inFlight(stats(0));

        assertFalse(inFlight.notRead());
        assertTrue(inFlight.consumers().isEmpty());
        verifyNoInteractions(management);
    }

    @Test
    @DisplayName("a reply bigger than the count promised is still cut at the limit, and says so")
    void capsWhatCameBack()
    {
        // deliveringCount is read a moment before the reply; a consumer window can fill in between.
        reply("[" + entry(CORE_CONSUMER, CORE_ELEMENT, CORE_ELEMENT) + ","
                + entry(OPENWIRE_CONSUMER, OPENWIRE_ELEMENT, OPENWIRE_ELEMENT) + "]");

        InFlight inFlight = service(3).inFlight(stats(3));

        assertTrue(inFlight.truncated());
        assertEquals(3, inFlight.listedCount());
        assertEquals(2, inFlight.consumers().size(), "the second consumer is still named, with what fitted");
    }

    @Test
    @DisplayName("locating one message by ID: found with its holder, a clean miss, or unknown when not all was read")
    void locatesByMessageId()
    {
        reply("[" + entry(CORE_CONSUMER, CORE_ELEMENT) + "," + entry(OPENWIRE_CONSUMER, OPENWIRE_ELEMENT) + "]");

        InFlightLookup found = service(5000).locate("work", 2, "ID:5f6ce32b");
        assertTrue(found.found());
        assertEquals("d46364be", found.holder().connectionId());
        assertEquals(126370L, found.message().coreId());

        InFlightLookup missing = service(5000).locate("work", 2, "ID:not-here");
        assertTrue(missing.checked());
        assertFalse(missing.found());

        // Cut at one message, the OpenWire consumer's was never looked at: that is not a "no".
        assertFalse(service(1).locate("work", 1, "ID:not-here").checked());
        assertFalse(service(1).locate("work", 2, "ID:not-here").checked(), "over the limit, not even asked");
    }

    private void reply(String json)
    {
        given(management.invoke(ResourceNames.QUEUE + "work", "listDeliveringMessagesAsJSON")).willReturn(json);
    }

    private static String entry(String consumerName, String... elements)
    {
        return "{\"consumerName\":\"" + consumerName + "\",\"elements\":[" + String.join(",", elements) + "]}";
    }

    private InFlightService service(int limit)
    {
        return new InFlightService(brokerSession, brokerInfo, limit);
    }

    private static QueueStats stats(long delivering)
    {
        return new QueueStats("work", "work", "ANYCAST", delivering, delivering, 0, 1, delivering, 0, true, false);
    }
}
