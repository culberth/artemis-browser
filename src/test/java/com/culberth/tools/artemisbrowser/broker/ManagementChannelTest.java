package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What can be checked without a broker, which is less than it looks.
 *
 * <p>
 * {@code JMSManagementHelper} refuses to build a request out of anything that is not an Artemis message — "Cannot send
 * a foreign message as a management message" — so a mocked {@code Session} cannot get as far as sending one. Every path
 * that involves an actual request therefore lives in {@code ManagementChannelIT}, against a real broker: the successful
 * round trip, the rejection, and the timeout.
 *
 * <p>
 * That leaves what happens either side of a request: naming the reply queue, shutting down after the connection has
 * already gone, and refusing an operation that is not on the read-only allowlist — which happens before any message
 * exists, so a mock is enough to prove nothing was sent.
 */
class ManagementChannelTest
{

    private Session session;
    private TemporaryQueue replyQueue;
    private MessageConsumer consumer;
    private MessageProducer producer;

    @BeforeEach
    void mocks() throws Exception
    {
        session = mock(Session.class);
        replyQueue = mock(TemporaryQueue.class);
        consumer = mock(MessageConsumer.class);
        producer = mock(MessageProducer.class);

        given(session.createTemporaryQueue()).willReturn(replyQueue);
        given(replyQueue.getQueueName()).willReturn("tmp-reply-9f2c");
        given(session.createQueue("activemq.management")).willReturn(mock(Queue.class));
        given(session.createProducer(org.mockito.ArgumentMatchers.any())).willReturn(producer);
        given(session.createConsumer(replyQueue)).willReturn(consumer);
    }

    @Test
    @DisplayName("the reply queue's name is exposed, so the listing can exclude our own plumbing")
    void exposesItsReplyQueueName() throws Exception
    {
        assertEquals("tmp-reply-9f2c", channel().replyQueueName());
    }

    @Test
    @DisplayName("the channel is built once, not per call")
    void buildsItsPlumbingUpFront() throws Exception
    {
        channel();

        verify(session).createTemporaryQueue();
        verify(session).createProducer(org.mockito.ArgumentMatchers.any());
        verify(session).createConsumer(replyQueue);
    }

    @Test
    @DisplayName("closing a channel whose connection is already gone does not throw")
    void closesQuietly() throws Exception
    {
        // The usual reason to close is that something already failed; a second failure on the way
        // out would replace the error worth reporting with a meaningless one.
        doThrow(new JMSException("already closed")).when(consumer).close();
        doThrow(new JMSException("already closed")).when(producer).close();
        doThrow(new JMSException("already closed")).when(replyQueue).delete();

        channel().close();
    }

    @ParameterizedTest
    @ValueSource(strings =
    { "removeAllMessages", "removeMessages", "moveMessages", "retryMessages", "expireMessages", "sendMessage",
            "createQueue", "destroyQueue", "deleteAddress", "pause", "resetAllMessageCounters", "createDivert",
            "addAddressSettings"
    })
    @DisplayName("an operation that could change the broker is refused before anything is sent")
    void refusesMutatingOperations(String operation) throws Exception
    {
        ManagementChannel channel = channel();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> channel.invoke("queue.orders", operation));

        assertTrue(thrown.getMessage().contains(operation), thrown.getMessage());
        verify(session, never()).createMessage();
        verify(producer, never()).send(any());
    }

    @Test
    @DisplayName("every allowlisted operation reads as a query, so a mutating one cannot slip in by name")
    void allowlistHoldsOnlyQueries()
    {
        // A name-shape check, not a proof: it catches "removeAllMessages" added in passing, and makes
        // adding anything that is not obviously a read a decision someone has to argue for here.
        Pattern query = Pattern.compile("^(list|get|browse|count|is)[A-Za-z]*$");
        for (String operation : ManagementChannel.READ_OPERATIONS)
        {
            assertTrue(query.matcher(operation).matches(), operation + " does not read as a query");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value =
    { "AMQ229069: no operation listBrokerConnections/0|false|UNSUPPORTED",
            "AMQ229032: User: viewer does not have permission='VIEW' on address mops.broker.getAddressSettingsAsJSON|false|DENIED",
            "AMQ229213: User: viewer does not have permission='CREATE_ADDRESS' for queue q on address q|false|DENIED",
            "Problem while retrieving attribute uptime|true|UNAVAILABLE",
            // The same phrase from an operation would be something else, so it is not trusted there.
            "Problem while retrieving attribute uptime|false|FAILED",
            "Unexpected character 'x' (Codepoint: 120) on [lineNumber=1, columnNumber=2]|false|FAILED",
            "no reason given|true|FAILED"
    })
    @DisplayName("a refusal is classified by the broker's own wording, as recorded against 2.44.0 and 2.55.0")
    void classifiesRefusals(String reason, boolean attribute, Availability expected)
    {
        assertEquals(expected, ManagementChannel.classify(reason, attribute));
    }

    @Test
    @DisplayName("an operation is remembered per resource type, not per queue")
    void namesTheResourceType()
    {
        assertEquals("queue", ManagementChannel.resourceType("queue.orders.eu"));
        assertEquals("broker", ManagementChannel.resourceType("broker"));
    }

    private ManagementChannel channel() throws JMSException
    {
        return new ManagementChannel(session, "activemq.management", 10000);
    }
}
