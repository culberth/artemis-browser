package com.culberth.tools.artemislab.worker;

import com.culberth.tools.artemislab.LabException;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import java.util.ArrayList;
import java.util.List;

/**
 * A consumer that receives and does not acknowledge, so its messages stay delivering until a person acknowledges or
 * releases them. Its connection must come from the lab's holding factory ({@code consumerWindowSize=0}): with the
 * default 1MB window the client buffers far more than it was asked to receive, and every buffered message counts as
 * delivering too.
 *
 * <p>
 * Also used without receiving anything: an open consumer that holds nothing (an idle consumer for imbalance) or a live
 * non-durable topic subscriber, whose subscription queue exists only while it does.
 */
public final class HeldConsumer extends Worker
{

    private final String clientId;
    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;
    private final List<Message> holding = new ArrayList<>();

    private HeldConsumer(String id, String runId, String description, String clientId, Connection connection,
            Session session, MessageConsumer consumer)
    {
        super(id, runId, "held consumer", description);
        this.clientId = clientId;
        this.connection = connection;
        this.session = session;
        this.consumer = consumer;
    }

    /**
     * Opens a CLIENT_ACKNOWLEDGE consumer on a started, verified connection, opened with its client id
     * ({@code TargetGuard.open(clientId)}). On failure the connection is closed.
     *
     * @param selector JMS selector, or null
     */
    public static HeldConsumer open(String id, String runId, String description, String clientId, Connection connection,
            Destination destination, String selector) throws JMSException
    {
        try
        {
            Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(destination, selector);
            connection.start();
            return new HeldConsumer(id, runId, description, clientId == null ? "" : clientId, connection, session,
                    consumer);
        }
        catch (JMSException | RuntimeException e)
        {
            connection.close();
            throw e;
        }
    }

    @Override
    public String clientId()
    {
        return clientId;
    }

    @Override
    public synchronized long held()
    {
        return holding.size();
    }

    /** Receives up to {@code count} more and holds them. Returns how many arrived within {@code timeoutMillis} each. */
    public synchronized int hold(int count, long timeoutMillis) throws JMSException
    {
        requireActive();
        int got = 0;
        while (got < count)
        {
            Message message = consumer.receive(timeoutMillis);
            if (message == null)
            {
                break;
            }
            holding.add(message);
            received.incrementAndGet();
            got++;
        }
        detail("holding " + holding.size());
        return got;
    }

    /** Acknowledges everything held: in CLIENT_ACKNOWLEDGE, acknowledging one acknowledges the session's lot. */
    public synchronized int acknowledge() throws JMSException
    {
        requireActive();
        int count = holding.size();
        if (count > 0)
        {
            holding.get(count - 1).acknowledge();
            acknowledged.addAndGet(count);
            holding.clear();
        }
        detail("acknowledged " + count + "; holding 0");
        return count;
    }

    @Override
    protected String close()
    {
        int returned = holding.size();
        holding.clear();
        try
        {
            connection.close();
        }
        catch (JMSException e)
        {
            return "closing failed: " + cause(e);
        }
        return returned + " held message(s) returned unacknowledged";
    }

    private void requireActive()
    {
        if (!active())
        {
            throw new LabException(description() + " is " + state().name().toLowerCase() + "; start it again.");
        }
    }
}
