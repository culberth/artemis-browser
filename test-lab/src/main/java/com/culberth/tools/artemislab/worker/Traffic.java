package com.culberth.tools.artemislab.worker;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.time.Duration;
import java.util.function.LongConsumer;

/**
 * Bounded traffic on its own thread: a producer sending, or a consumer receiving and acknowledging, at a target rate
 * for a fixed time. It stops by itself at the end, on request, or at {@code maxMessages} — whichever comes first — and
 * reports what actually happened, which is what a rate is checked against, not what was asked for.
 */
public final class Traffic extends Worker
{

    public enum Direction
    {
        PRODUCE, CONSUME
    }

    private final Direction direction;
    private final Connection connection;
    private final String queue;
    private final int perSecond;
    private final Duration duration;
    private final long maxMessages;
    private final int bodyBytes;
    private final LongConsumer onSentBytes;
    private final Thread thread;
    private volatile boolean stopping;

    private Traffic(String id, String runId, String description, Direction direction, Connection connection,
            String queue, int perSecond, Duration duration, long maxMessages, int bodyBytes, LongConsumer onSentBytes)
    {
        super(id, runId, direction == Direction.PRODUCE ? "producer" : "consumer", description);
        this.direction = direction;
        this.connection = connection;
        this.queue = queue;
        this.perSecond = perSecond;
        this.duration = duration;
        this.maxMessages = maxMessages;
        this.bodyBytes = bodyBytes;
        this.onSentBytes = onSentBytes;
        this.thread = new Thread(this::loop, "lab-traffic-" + id);
        this.thread.setDaemon(true);
    }

    /**
     * Starts the thread on a verified connection.
     *
     * @param onSentBytes told the body bytes of each send, for the run's budget
     */
    public static Traffic start(String id, String runId, Direction direction, Connection connection, String queue,
            int perSecond, Duration duration, long maxMessages, int bodyBytes, LongConsumer onSentBytes)
    {
        String description = (direction == Direction.PRODUCE ? "produce to " : "consume from ") + queue + " at "
                + perSecond + "/s for " + duration.toSeconds() + "s";
        Traffic traffic = new Traffic(id, runId, description, direction, connection, queue, perSecond, duration,
                maxMessages, bodyBytes, onSentBytes);
        traffic.thread.start();
        return traffic;
    }

    @Override
    public String clientId()
    {
        return "";
    }

    private void loop()
    {
        long intervalNanos = 1_000_000_000L / perSecond;
        long deadline = System.nanoTime() + duration.toNanos();
        long next = System.nanoTime();
        try
        {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = direction == Direction.PRODUCE
                    ? session.createProducer(session.createQueue(queue))
                    : null;
            MessageConsumer consumer = direction == Direction.CONSUME
                    ? session.createConsumer(session.createQueue(queue))
                    : null;
            String body = "x".repeat(bodyBytes);
            long done = 0;
            while (!stopping && System.nanoTime() < deadline && done < maxMessages)
            {
                long wait = next - System.nanoTime();
                if (wait > 0)
                {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                next += intervalNanos;
                if (producer != null)
                {
                    TextMessage message = session.createTextMessage(body);
                    message.setStringProperty("labRun", runId());
                    producer.send(message, DeliveryMode.PERSISTENT, 4, 0);
                    sent.incrementAndGet();
                    onSentBytes.accept(bodyBytes);
                    done++;
                }
                else
                {
                    Message message = consumer.receive(Math.max(1, intervalNanos / 1_000_000));
                    if (message != null)
                    {
                        received.incrementAndGet();
                        acknowledged.incrementAndGet();
                        done++;
                    }
                }
                detail((producer != null ? "sent " + sent() : "received and acknowledged " + received()));
            }
            String outcome = (producer != null ? "sent " + sent() : "received and acknowledged " + received())
                    + (stopping ? ", stopped on request"
                            : done >= maxMessages ? ", stopped at the message limit"
                                    : ", ran its full " + duration.toSeconds() + "s");
            closeQuietly();
            finish(State.STOPPED, outcome);
        }
        catch (InterruptedException e)
        {
            closeQuietly();
            finish(State.STOPPED,
                    (direction == Direction.PRODUCE ? "sent " + sent() : "received " + received()) + ", interrupted");
        }
        catch (JMSException | RuntimeException e)
        {
            closeQuietly();
            finish(State.FAILED, cause(e) + " after " + (sent() + received()) + " message(s)");
        }
    }

    @Override
    protected String close()
    {
        stopping = true;
        thread.interrupt();
        try
        {
            thread.join(5000);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        closeQuietly();
        return direction == Direction.PRODUCE ? "sent " + sent() : "received and acknowledged " + received();
    }

    private void closeQuietly()
    {
        try
        {
            connection.close();
        }
        catch (JMSException ignored)
        {
            // Already closed, or the broker is gone; either way nothing more is sent.
        }
    }
}
