package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Built from {@code listQueues} rows recorded on 2.55.0 and 2.57.0 (identical), one queue per setting — see
 * {@code .claude/memory.md}. The configuration fields are string-quoted like the counters.
 */
class QueueBehaviorTest
{

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The settings part of a stock queue's row, every one at its default. */
    private static final String PLAIN = "\"exclusive\":\"false\",\"lastValue\":\"false\",\"lastValueKey\":\"\","
            + "\"ringSize\":\"-1\",\"groupRebalance\":\"false\",\"groupRebalancePauseDispatch\":\"false\","
            + "\"groupBuckets\":\"-1\",\"groupFirstKey\":\"\",\"consumersBeforeDispatch\":\"0\","
            + "\"delayBeforeDispatch\":\"-1\",\"purgeOnNoConsumers\":\"false\",\"maxConsumers\":\"-1\","
            + "\"enabled\":\"true\"";

    @Test
    @DisplayName("a stock queue is a plain FIFO: nothing special, no badges")
    void plainQueue()
    {
        QueueBehavior behavior = behavior(PLAIN);

        assertTrue(behavior.known());
        assertFalse(behavior.special());
        assertTrue(behavior.badges().isEmpty());
        assertEquals(Availability.UNSUPPORTED, behavior.nonDestructive().availability(),
                "non-destructive is not reported per queue on either version");
    }

    @Test
    @DisplayName("last-value is the key being set: the broker reports lastValue false on a queue that is one")
    void lastValueFromTheKey()
    {
        QueueBehavior behavior = behavior(PLAIN.replace("\"lastValueKey\":\"\"", "\"lastValueKey\":\"k\""));

        assertEquals("k", behavior.lastValueOn());
        assertEquals(List.of("last-value"), behavior.badges());
        assertTrue(behavior.effects().get(0).consequence().contains("appear in no counter"));
    }

    @Test
    @DisplayName("a ring size of -1 is no ring; 3 is a ring of three")
    void ring()
    {
        assertNull(behavior(PLAIN).ring());
        QueueBehavior ring = behavior(PLAIN.replace("\"ringSize\":\"-1\"", "\"ringSize\":\"3\""));
        assertEquals(3L, ring.ring());
        assertEquals(List.of("ring 3"), ring.badges());
    }

    @Test
    @DisplayName("exclusive, or at most one consumer, means one consumer takes everything")
    void singleConsumer()
    {
        assertTrue(behavior(PLAIN.replace("\"exclusive\":\"false\"", "\"exclusive\":\"true\"")).singleConsumer());
        assertTrue(behavior(PLAIN.replace("\"maxConsumers\":\"-1\"", "\"maxConsumers\":\"1\"")).singleConsumer());
        assertFalse(behavior(PLAIN).singleConsumer());
    }

    @Test
    @DisplayName("waiting for two consumers gates dispatch while one is attached, not while none or two are")
    void dispatchGate()
    {
        QueueBehavior gated = behavior(
                PLAIN.replace("\"consumersBeforeDispatch\":\"0\"", "\"consumersBeforeDispatch\":\"2\""));

        assertTrue(gated.dispatchGated(1));
        assertFalse(gated.dispatchGated(0), "no consumer is the ordinary finding, not the gate");
        assertFalse(gated.dispatchGated(2));
        assertEquals(List.of("waits for 2 consumers"), gated.badges());
    }

    @Test
    @DisplayName("purge on no consumers says the purged messages count as killed")
    void purge()
    {
        QueueBehavior purges = behavior(
                PLAIN.replace("\"purgeOnNoConsumers\":\"false\"", "\"purgeOnNoConsumers\":\"true\""));

        assertTrue(purges.purges());
        assertTrue(purges.effects().get(0).consequence().contains("counted as killed"));
    }

    @Test
    @DisplayName("a listing without the settings leaves them unsupported, and a malformed one failed — never a default")
    void absentAndMalformed()
    {
        QueueBehavior behavior = behavior("\"ringSize\":\"lots\"");

        assertEquals(Availability.FAILED, behavior.ringSize().availability());
        assertEquals(Availability.UNSUPPORTED, behavior.exclusive().availability());
        assertFalse(behavior.singleConsumer());
        assertTrue(behavior.effects().isEmpty());
    }

    @Test
    @DisplayName("a row built from counters alone says the configuration was not read")
    void notCollected()
    {
        assertFalse(QueueBehavior.NOT_COLLECTED.known());
        assertEquals(Availability.NOT_COLLECTED, QueueBehavior.NOT_COLLECTED.ringSize().availability());
    }

    @Test
    @DisplayName("the directory attaches the behavior to the overview, the stats and the subscriptions")
    void directoryAttachesBehavior()
    {
        BrokerSession session = org.mockito.Mockito.mock(BrokerSession.class);
        ManagementChannel management = org.mockito.Mockito.mock(ManagementChannel.class);
        org.mockito.BDDMockito.given(session.requireManagement()).willReturn(management);
        String row = "{\"name\":\"it-q-ring\",\"address\":\"it-q-ring\",\"routingType\":\"ANYCAST\","
                + "\"messageCount\":\"3\",\"messagesAdded\":\"10\","
                + PLAIN.replace("\"ringSize\":\"-1\"", "\"ringSize\":\"3\"") + "}";
        org.mockito.BDDMockito
                .given(management.invoke(org.mockito.ArgumentMatchers.eq(ResourceNames.BROKER),
                        org.mockito.ArgumentMatchers.eq("listQueues"), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(200)))
                .willReturn("{\"data\":[" + row + "],\"count\":1}");
        QueueDirectory directory = new QueueDirectory(session);

        assertEquals(3L, directory.overview().get(0).behavior().ring());
        assertEquals(3L, directory.stats("it-q-ring").behavior().ring());
        assertEquals(3L, directory.onAddress("it-q-ring").get(0).behavior().ring());
    }

    @Test
    @DisplayName("the group count is a number read per queue, and a refusal is a reading")
    void groupCount()
    {
        BrokerSession session = org.mockito.Mockito.mock(BrokerSession.class);
        ManagementChannel management = org.mockito.Mockito.mock(ManagementChannel.class);
        org.mockito.BDDMockito.given(session.requireManagement()).willReturn(management);
        org.mockito.BDDMockito.given(management.attribute(ResourceNames.QUEUE + "it-q-group", "groupCount"))
                .willReturn(2);
        org.mockito.BDDMockito.given(management.attribute(ResourceNames.QUEUE + "gone", "groupCount"))
                .willThrow(new ManagementRefusal(Availability.UNAVAILABLE, "Problem while retrieving attribute"));

        assertEquals(2L, new QueueDirectory(session).groupCount("it-q-group").value());
        assertEquals(Availability.UNAVAILABLE, new QueueDirectory(session).groupCount("gone").availability());
    }

    private static QueueBehavior behavior(String fields)
    {
        JsonNode node = JSON.readTree("{\"name\":\"q\"," + fields + "}");
        return QueueBehavior.from(node);
    }
}
