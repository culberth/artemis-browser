package com.culberth.tools.artemisbrowser.broker;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;

/**
 * Request/reply plumbing for Artemis's management address.
 *
 * <p>
 * Queue listing is not a JMS operation — the JMS API has no notion of "what queues exist". It is a management query: a
 * message sent to {@code activemq.management} naming a resource and an operation, answered on a reply queue. Doing it
 * this way rather than over JMX means no extra port and no Jolokia; the broker user just needs {@code manage}
 * permission on that address.
 *
 * <p>
 * The producer, consumer and temporary reply queue are created once and reused. Every method is synchronized because a
 * JMS {@link Session} is not thread-safe and this one is shared by whatever requests the user's HTTP session makes.
 */
public class ManagementChannel implements AutoCloseable
{

    private final Session session;
    private final TemporaryQueue replyQueue;
    private final MessageProducer producer;
    private final MessageConsumer consumer;
    private final long timeoutMillis;
    private final String replyQueueName;

    ManagementChannel(Session session, String managementAddress, long timeoutMillis) throws JMSException
    {
        this.session = session;
        this.timeoutMillis = timeoutMillis;
        this.replyQueue = session.createTemporaryQueue();
        this.producer = session.createProducer(session.createQueue(managementAddress));
        this.consumer = session.createConsumer(replyQueue);
        this.replyQueueName = replyQueue.getQueueName();
    }

    /**
     * The name of this channel's own temporary reply queue.
     *
     * <p>
     * The broker counts it as a queue like any other, so without excluding it the tool lists its own plumbing as a
     * browsable queue — with a UUID for a name and a different one each time the user reconnects.
     */
    public String replyQueueName()
    {
        return replyQueueName;
    }

    /** Invoke a management operation, e.g. {@code broker.getQueueNames()}. */
    public synchronized Object invoke(String resource, String operation, Object... params)
    {
        return exchange(resource + "." + operation + "()", request ->
        {
            if (params.length == 0)
            {
                JMSManagementHelper.putOperationInvocation(request, resource, operation);
            }
            else
            {
                JMSManagementHelper.putOperationInvocation(request, resource, operation, params);
            }
        });
    }

    /** Read a management attribute, e.g. {@code queue.DLQ.messageCount}. */
    public synchronized Object attribute(String resource, String attribute)
    {
        return exchange(resource + "." + attribute,
                request -> JMSManagementHelper.putAttribute(request, resource, attribute));
    }

    private Object exchange(String what, RequestBuilder builder)
    {
        try
        {
            Message request = session.createMessage();
            builder.build(request);
            request.setJMSReplyTo(replyQueue);
            producer.send(request);

            Message reply = consumer.receive(timeoutMillis);
            if (reply == null)
            {
                throw new BrokerException("The broker did not answer " + what + " within " + timeoutMillis
                        + "ms. It may be under load, or the management address may be" + " disabled.");
            }
            if (!JMSManagementHelper.hasOperationSucceeded(reply))
            {
                throw new BrokerException("The broker rejected " + what + ": " + describeFailure(reply)
                        + ". Check that this user has the 'manage' permission on activemq.management.");
            }
            return JMSManagementHelper.getResult(reply);
        }
        catch (BrokerException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new BrokerException("Management call " + what + " failed: " + e.getMessage(), e);
        }
    }

    private String describeFailure(Message reply)
    {
        try
        {
            Object result = JMSManagementHelper.getResult(reply);
            return result == null ? "no reason given" : String.valueOf(result);
        }
        catch (Exception e)
        {
            return "no reason given";
        }
    }

    @Override
    public void close()
    {
        closeQuietly(consumer::close);
        closeQuietly(producer::close);
        closeQuietly(replyQueue::delete);
    }

    private void closeQuietly(ThrowingRunnable action)
    {
        try
        {
            action.run();
        }
        catch (Exception ignored)
        {
            // Closing down a channel whose connection may already be gone; nothing useful to do.
        }
    }

    @FunctionalInterface
    private interface RequestBuilder
    {
        void build(Message request) throws JMSException;
    }

    @FunctionalInterface
    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
