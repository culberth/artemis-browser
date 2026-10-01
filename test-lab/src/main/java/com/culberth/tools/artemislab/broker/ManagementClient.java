package com.culberth.tools.artemislab.broker;

import com.culberth.tools.artemislab.LabException;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;

/**
 * Management request/reply on one session of a connection the caller has already verified. Unlike Artemis Browser's
 * channel it has no allowlist — creating and deleting owned resources is what the lab uses it for — so it must only
 * ever be built on a connection {@link TargetGuard} handed out.
 */
public final class ManagementClient implements AutoCloseable
{

    private final Session session;
    private final MessageProducer producer;
    private final TemporaryQueue reply;
    private final MessageConsumer consumer;
    private final long timeoutMillis;

    public ManagementClient(Connection connection, Duration timeout) throws JMSException
    {
        this.session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        this.producer = session.createProducer(session.createQueue("activemq.management"));
        this.reply = session.createTemporaryQueue();
        this.consumer = session.createConsumer(reply);
        this.timeoutMillis = timeout.toMillis();
    }

    public Object attribute(String resource, String name) throws JMSException
    {
        Message request = session.createMessage();
        JMSManagementHelper.putAttribute(request, resource, name);
        return call(request, resource + "." + name);
    }

    public Object invoke(String resource, String operation, Object... arguments) throws JMSException
    {
        Message request = session.createMessage();
        try
        {
            JMSManagementHelper.putOperationInvocation(request, resource, operation, arguments);
        }
        catch (Exception e)
        {
            throw new LabException("Could not encode " + operation + ": " + e.getMessage(), e);
        }
        return call(request, resource + "." + operation + "()");
    }

    /** The broker's queue names, from the {@code queueNames} attribute. */
    public Set<String> queueNames() throws JMSException
    {
        return names(attribute(ResourceNames.BROKER, "queueNames"));
    }

    public Set<String> addressNames() throws JMSException
    {
        return names(attribute(ResourceNames.BROKER, "addressNames"));
    }

    public String nodeId() throws JMSException
    {
        return String.valueOf(attribute(ResourceNames.BROKER, "nodeID"));
    }

    public String version() throws JMSException
    {
        return String.valueOf(attribute(ResourceNames.BROKER, "version"));
    }

    /** A queue's {@code messageCount}; the queue resource takes the bare name, never the FQQN. */
    public long messageCount(String queue) throws JMSException
    {
        Object value = attribute(ResourceNames.QUEUE + queue, "messageCount");
        if (value instanceof Number number)
        {
            return number.longValue();
        }
        throw new LabException("Unexpected messageCount for " + queue + ": " + value);
    }

    private Object call(Message request, String what) throws JMSException
    {
        request.setJMSReplyTo(reply);
        producer.send(request);
        Message answer = consumer.receive(timeoutMillis);
        if (answer == null)
        {
            throw new LabException("No reply to " + what + " within " + timeoutMillis + "ms.");
        }
        Object result;
        try
        {
            result = JMSManagementHelper.getResult(answer);
        }
        catch (Exception e)
        {
            throw new LabException("Could not read the reply to " + what + ": " + e.getMessage(), e);
        }
        if (!JMSManagementHelper.hasOperationSucceeded(answer))
        {
            throw new LabException(what + " failed: " + result);
        }
        return result;
    }

    private static Set<String> names(Object value)
    {
        if (value instanceof Object[] array)
        {
            return Arrays.stream(array).map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        throw new LabException("Unexpected name list from the broker: " + value);
    }

    @Override
    public void close() throws JMSException
    {
        try
        {
            consumer.close();
            producer.close();
            reply.delete();
        }
        finally
        {
            session.close();
        }
    }
}
