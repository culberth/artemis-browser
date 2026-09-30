package com.culberth.tools.artemisbrowser.broker;

import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
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

    /**
     * What this broker has already said it cannot do, so a refresh does not ask again. Only what cannot change while
     * connected is kept: an unknown operation, and a broker attribute that would not be read. A queue's attribute is
     * not kept, because the same reply also means the queue has gone; nor is a denial, which a reloaded security
     * setting can lift. Belongs to the channel, so it goes when the connection does — a different broker gets asked
     * afresh.
     */
    private final Map<String, ManagementRefusal> knownRefusals = new HashMap<>();

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

    /**
     * Every management operation this tool may invoke. Anything else is refused before a request is built.
     *
     * <p>
     * The management address will run whatever the broker user is permitted to — {@code removeAllMessages},
     * {@code moveMessages}, {@code destroyQueue} — so read-only cannot rest on nobody typing one of those. Adding an
     * operation here is the deliberate step; {@code ManagementChannelTest} checks each name reads as a query.
     * Attributes need no such list: an attribute request is a get by construction.
     */
    static final Set<String> READ_OPERATIONS = Set.of(
            // broker.*
            "listQueues", "listAddresses", "getAcceptorsAsJSON", "listConnectionsAsJSON", "listAllConsumersAsJSON",
            "listProducersInfoAsJSON", "listConsumers", "getAddressSettingsAsJSON", "getDivertNames", "listConnections",
            "listSessions", "listProducers", "listNetworkTopology", "listBrokerConnections",
            // queue.*
            "browse", "countMessages", "listScheduledMessagesAsJSON", "listDeliveringMessagesAsJSON");

    /** Invoke a read-only management operation, e.g. {@code broker.listQueues(...)}. */
    public synchronized Object invoke(String resource, String operation, Object... params)
    {
        if (!READ_OPERATIONS.contains(operation))
        {
            throw new IllegalArgumentException("Refusing management operation " + resource + "." + operation
                    + "(): it is not on this tool's read-only allowlist (ManagementChannel.READ_OPERATIONS).");
        }
        String capability = resourceType(resource) + "." + operation + "/" + params.length;
        ManagementRefusal known = knownRefusals.get(capability);
        if (known != null)
        {
            throw known;
        }
        return exchange(resource + "." + operation + "()", capability, false, request ->
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
        // Only the broker's own attributes are remembered: the broker resource cannot go away, so a
        // failure on it is about the attribute, not the resource.
        String capability = ResourceNames.BROKER.equals(resource) ? resource + "." + attribute : null;
        ManagementRefusal known = capability == null ? null : knownRefusals.get(capability);
        if (known != null)
        {
            throw known;
        }
        return exchange(resource + "." + attribute, capability, true,
                request -> JMSManagementHelper.putAttribute(request, resource, attribute));
    }

    /** {@code queue.orders} → {@code queue}; {@code broker} → {@code broker}. An operation exists per resource type. */
    static String resourceType(String resource)
    {
        int dot = resource.indexOf('.');
        return dot < 0 ? resource : resource.substring(0, dot);
    }

    /**
     * Why the broker said no, from how it said it. The wording is the broker's own and checked on 2.44.0 and 2.55.0:
     * {@code AMQ229069} for an operation the version does not have, {@code AMQ229032} for one this user may not run
     * (only possible per operation with {@code management-message-rbac}), and for an attribute one fixed phrase that
     * covers missing, gone and denied alike.
     */
    static Availability classify(String reason, boolean attribute)
    {
        String text = reason == null ? "" : reason;
        if (text.contains("AMQ229069"))
        {
            return Availability.UNSUPPORTED;
        }
        if (text.contains("AMQ229032") || text.contains("AMQ229213") || text.contains("does not have permission"))
        {
            return Availability.DENIED;
        }
        if (attribute && text.startsWith("Problem while retrieving attribute"))
        {
            return Availability.UNAVAILABLE;
        }
        return Availability.FAILED;
    }

    private Object exchange(String what, String capability, boolean attribute, RequestBuilder builder)
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
                String reason = describeFailure(reply);
                Availability availability = classify(reason, attribute);
                ManagementRefusal refusal = new ManagementRefusal(availability,
                        refusalMessage(what, reason, availability));
                if (capability != null
                        && (availability == Availability.UNSUPPORTED || availability == Availability.UNAVAILABLE))
                {
                    knownRefusals.put(capability, refusal);
                }
                throw refusal;
            }
            return JMSManagementHelper.getResult(reply);
        }
        catch (BrokerException e)
        {
            throw e;
        }
        catch (JMSSecurityException e)
        {
            // Thrown by send when this user lacks 'manage' on the management address. The session
            // survives it — checked against a broker — so this is a refusal, not a lost connection,
            // and treating it as one would throw the user back to the connect form for good.
            throw new ManagementRefusal(Availability.DENIED, "The broker refused " + what + ": " + e.getMessage()
                    + ". This user needs the 'manage' permission on activemq.management.", e);
        }
        catch (JMSException e)
        {
            // The channel is built on one session; a JMS-level failure on it means the session is
            // gone, not that this particular call was bad. Everything else on this connection will
            // fail the same way until the user connects again, so say so once, clearly.
            throw new ConnectionLostException(
                    "The connection to the broker was lost while running " + what + ": " + e.getMessage(), e);
        }
        catch (Exception e)
        {
            throw new BrokerException("Management call " + what + " failed: " + e.getMessage(), e);
        }
    }

    private static String refusalMessage(String what, String reason, Availability availability)
    {
        return switch (availability)
        {
            case UNSUPPORTED -> "This broker does not support " + what + " (" + reason + ").";
            case DENIED -> "The broker refused " + what + " for this user: " + reason + ".";
            case UNAVAILABLE -> "The broker would not return " + what + " (" + reason
                    + "). It may not exist on this broker version, or this user may not be permitted to read it.";
            default -> "The broker rejected " + what + ": " + reason
                    + ". Check that this user has the 'manage' permission on activemq.management.";
        };
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
