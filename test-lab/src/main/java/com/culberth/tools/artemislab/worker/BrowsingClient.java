package com.culberth.tools.artemislab.worker;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import java.util.Enumeration;

/**
 * A JMS {@code QueueBrowser} held open part-way through its enumeration. The broker sees a browse-only consumer on the
 * queue for as long as it is open: a client is attached, and nothing will ever be consumed. That is the case Q01 needs
 * told apart from a real consumer.
 */
public final class BrowsingClient extends Worker
{

    private final Connection connection;
    private final QueueBrowser browser;

    private BrowsingClient(String id, String runId, String description, Connection connection, QueueBrowser browser)
    {
        super(id, runId, "browsing client", description);
        this.connection = connection;
        this.browser = browser;
    }

    /** Opens a browser on a verified connection and reads one message, which is what makes the broker create it. */
    public static BrowsingClient open(String id, String runId, String description, Connection connection, String queue)
            throws JMSException
    {
        try
        {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            QueueBrowser browser = session.createBrowser(session.createQueue(queue));
            Enumeration<?> messages = browser.getEnumeration();
            BrowsingClient client = new BrowsingClient(id, runId, description, connection, browser);
            if (messages.hasMoreElements())
            {
                messages.nextElement();
                client.received.incrementAndGet();
            }
            client.detail("browsing, " + client.received() + " read");
            return client;
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
        return "";
    }

    @Override
    protected String close()
    {
        try
        {
            browser.close();
            connection.close();
            return "browser closed; nothing was consumed";
        }
        catch (JMSException e)
        {
            return "closing failed: " + cause(e);
        }
    }
}
