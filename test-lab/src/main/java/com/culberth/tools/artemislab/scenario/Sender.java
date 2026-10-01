package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.run.SentMessage;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One worker's sends: one connection, one session, used from one thread. Every message gets {@code labRun} and
 * {@code labSeq}, is checked against the body limit and for cancellation before it goes, and is added to the run's send
 * manifest once the broker has accepted it. Bounded sends block for the broker's acknowledgment — persistent ones by
 * JMS's rules, and every one under the factory's {@code callTimeout}.
 */
public final class Sender implements AutoCloseable
{

    private static final int FLUSH_EVERY = 200;

    /**
     * @param deliveryMode  {@link DeliveryMode#PERSISTENT} or {@link DeliveryMode#NON_PERSISTENT}
     * @param priority      0-9
     * @param timeToLive    millis, 0 for none
     * @param deliveryDelay millis before the broker delivers it; above 0 makes it a scheduled message
     */
    public record Options(int deliveryMode, int priority, long timeToLive, long deliveryDelay)
    {

        public static final Options PERSISTENT = new Options(DeliveryMode.PERSISTENT, 4, 0, 0);

        public Options withPriority(int value)
        {
            return new Options(deliveryMode, value, timeToLive, deliveryDelay);
        }

        public Options withDeliveryMode(int value)
        {
            return new Options(value, priority, timeToLive, deliveryDelay);
        }

        public Options withDeliveryDelay(long value)
        {
            return new Options(deliveryMode, priority, timeToLive, value);
        }
    }

    private final Fixture fixture;
    private final Connection connection;
    private final Session session;
    private final Map<String, MessageProducer> producers = new HashMap<>();
    private final List<SentMessage> pending = new ArrayList<>();

    Sender(Fixture fixture, Connection connection) throws JMSException
    {
        this.fixture = fixture;
        this.connection = connection;
        try
        {
            this.session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        }
        catch (JMSException | RuntimeException e)
        {
            connection.close();
            throw e;
        }
    }

    /** For building messages. */
    public Session session()
    {
        return session;
    }

    /** Sends and records one message; returns its JMS message id. */
    public String send(String queue, int seq, Message message, String kind, int bodyBytes, String note, Options options)
            throws JMSException
    {
        fixture.job().checkCancelled();
        fixture.limits().checkBodyBytes(bodyBytes);
        message.setStringProperty("labRun", fixture.runId());
        message.setIntProperty("labSeq", seq);
        MessageProducer producer = producers.get(queue);
        if (producer == null)
        {
            producer = session.createProducer(session.createQueue(queue));
            producers.put(queue, producer);
        }
        producer.setDeliveryDelay(options.deliveryDelay());
        producer.send(message, options.deliveryMode(), options.priority(), options.timeToLive());
        String id = message.getJMSMessageID();
        pending.add(new SentMessage(queue, seq, kind, id, bodyBytes, note == null ? "" : note));
        if (pending.size() >= FLUSH_EVERY)
        {
            flush();
            fixture.job().progress("sent " + seq + " to " + queue);
        }
        return id;
    }

    public void flush()
    {
        fixture.recordSent(List.copyOf(pending));
        pending.clear();
    }

    @Override
    public void close() throws JMSException
    {
        try
        {
            flush();
        }
        finally
        {
            try
            {
                session.close();
            }
            finally
            {
                connection.close();
            }
        }
    }
}
