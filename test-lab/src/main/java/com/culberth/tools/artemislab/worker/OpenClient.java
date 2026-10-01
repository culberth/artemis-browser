package com.culberth.tools.artemislab.worker;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Session;

/**
 * An idle client kept connected so the broker's client views have something to show: a connection with or without a
 * client id, a number of sessions, and an open producer or consumer on a queue in each. It sends and receives nothing.
 */
public final class OpenClient extends Worker
{

    public enum Role
    {
        PRODUCER, CONSUMER
    }

    private final String clientId;
    private final Connection connection;

    private OpenClient(String id, String runId, String description, String clientId, Connection connection)
    {
        super(id, runId, "open client", description);
        this.clientId = clientId;
        this.connection = connection;
    }

    /** Opens {@code sessions} sessions on a verified connection, each with one idle producer or consumer. */
    public static OpenClient open(String id, String runId, String description, String clientId, Connection connection,
            String queue, Role role, int sessions) throws JMSException
    {
        try
        {
            for (int i = 0; i < sessions; i++)
            {
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                if (role == Role.PRODUCER)
                {
                    session.createProducer(session.createQueue(queue));
                }
                else
                {
                    session.createConsumer(session.createQueue(queue));
                }
            }
            // Never asked to receive; with the holding factory window of 0 the broker delivers it nothing.
            connection.start();
            return new OpenClient(id, runId, description, clientId == null ? "" : clientId, connection);
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
    protected String close()
    {
        try
        {
            connection.close();
            return "disconnected";
        }
        catch (JMSException e)
        {
            return "closing failed: " + cause(e);
        }
    }
}
