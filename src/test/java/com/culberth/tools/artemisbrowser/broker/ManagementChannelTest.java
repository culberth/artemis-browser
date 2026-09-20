package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * That leaves the two things that happen either side of a request: naming the reply queue, and shutting down after the
 * connection has already gone.
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

    private ManagementChannel channel() throws JMSException
    {
        return new ManagementChannel(session, "activemq.management", 10000);
    }
}
