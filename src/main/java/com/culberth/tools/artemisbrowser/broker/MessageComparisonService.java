package com.culberth.tools.artemisbrowser.broker;

import com.culberth.tools.artemisbrowser.broker.MessageComparison.Side;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads two messages and compares them, on request.
 *
 * <p>
 * Each message is read exactly as the message page reads it — a JMS {@code QueueBrowser} with a {@code JMSMessageID}
 * selector on a queue the broker lists — so nothing is consumed, and a message delivered to a consumer and not yet
 * acknowledged is never returned: in-flight bodies are not read here or anywhere else. One queue listing finds both
 * queues; no management operation is added.
 */
@Service
public class MessageComparisonService
{

    private static final DateTimeFormatter READ_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final QueueDirectory queueDirectory;
    private final QueueBrowseService browseService;
    private final MessageComparer comparer;
    private final int maxBodyChars;

    public MessageComparisonService(QueueDirectory queueDirectory, QueueBrowseService browseService,
            @Value("${artemis.message-compare.max-body-chars:100000}") int maxBodyChars,
            @Value("${artemis.message-compare.max-align-cells:1000000}") long maxAlignCells,
            @Value("${artemis.message-compare.max-shown-lines:2000}") int maxShownLines,
            @Value("${artemis.message-compare.max-json-values:5000}") int maxJsonValues)
    {
        this.queueDirectory = queueDirectory;
        this.browseService = browseService;
        this.maxBodyChars = maxBodyChars;
        this.comparer = new MessageComparer(maxBodyChars, maxAlignCells, maxShownLines, maxJsonValues);
    }

    public int maxBodyChars()
    {
        return maxBodyChars;
    }

    /**
     * Both messages, read one after the other, and their differences when both could be read.
     *
     * @throws BrokerException when the queue listing itself fails, so neither side can be looked for
     */
    public MessageComparison compare(String leftQueue, String leftId, String rightQueue, String rightId)
    {
        List<QueueOverview> queues = queueDirectory.overview();
        Side left = read(queues, leftQueue, leftId);
        Side right = read(queues, rightQueue, rightId);
        boolean same = leftQueue.equals(rightQueue) && leftId.equals(rightId);
        if (!left.available() || !right.available())
        {
            return new MessageComparison(left, right, null, null, null, same);
        }
        MessageDetail a = left.message();
        MessageDetail b = right.message();
        return new MessageComparison(left, right, comparer.headers(a, b), comparer.properties(a, b),
                comparer.body(a, b), same);
    }

    private Side read(List<QueueOverview> queues, String queueName, String messageId)
    {
        QueueOverview queue = queues.stream().filter(candidate -> candidate.name().equals(queueName)).findFirst()
                .orElse(null);
        if (queue == null)
        {
            return new Side(queueName, messageId, "", null, "No queue named '" + queueName + "' on this broker.");
        }
        String browseName = queue.address() == null || queue.address().equals(queue.name()) ? queue.name()
                : queue.address() + "::" + queue.name();
        MessageDetail detail;
        try
        {
            detail = browseService.detail(queueName, browseName, messageId);
        }
        catch (BrokerException e)
        {
            return new Side(queueName, messageId, READ_TIME.format(Instant.now()), null,
                    "Could not be read: " + e.getMessage());
        }
        String readAt = READ_TIME.format(Instant.now());
        if (detail == null)
        {
            return new Side(queueName, messageId, readAt, null, notBrowsable(queue));
        }
        return new Side(queueName, messageId, readAt, detail, null);
    }

    /**
     * Why a message that was selected may not be there now — never "it is empty". The counts are from the listing read
     * just before, so they describe the queue, not this message.
     */
    private static String notBrowsable(QueueOverview queue)
    {
        StringBuilder why = new StringBuilder("Not on '").append(queue.name())
                .append("' when read: it may have been consumed, expired or moved since it was selected. That is"
                        + " not an empty message, and nothing was compared.");
        if (queue.deliveringCount() > 0)
        {
            why.append(" The queue reports ").append(queue.deliveringCount()).append(
                    " in flight to a consumer; a browse does not return those, and their bodies are never" + " read.");
        }
        if (queue.scheduledCount() > 0)
        {
            why.append(" It also reports ").append(queue.scheduledCount())
                    .append(" scheduled, which a browse does not return either.");
        }
        return why.toString();
    }
}
