package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
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

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
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
        return new InFlightService(brokerSession, limit);
    }

    private static QueueStats stats(long delivering)
    {
        return new QueueStats("work", "work", "ANYCAST", delivering, delivering, 0, 1, delivering, 0, true, false);
    }
}
