package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * One message a prepared transaction will act on when it is resolved: a send that has not reached any queue yet, or a
 * receive whose message stays on its queue, counted as delivering, until then.
 *
 * <p>
 * From {@code listPreparedTransactionDetailsAsJSON}, as recorded on 2.55.0 and 2.57.0: an operation of
 * {@code "(+) send"} or {@code "(-) receive"}, the message type, and its headers with its properties inline. There is
 * no body and no queue name — a receive names only the address its message was sent to.
 *
 * @param operation    what the transaction does with it
 * @param rawOperation the broker's own words, kept for an operation this tool does not recognise
 * @param messageId    the broker's numeric message id, as text
 * @param userId       the id clients and this tool's searches use ({@code ID:…}); empty when it has none
 * @param timestamp    when the message was sent, epoch millis by the sending client's clock; 0 when not given
 * @param properties   the message's own properties, Artemis's internal ones left out
 */
public record TransactionMessage(Operation operation, String rawOperation, String type, String address,
        String messageId, String userId, long timestamp, boolean durable, int priority, Map<String, String> properties)
{

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public enum Operation
    {
        /** Sent inside the transaction: on no queue until it commits, discarded if it rolls back. */
        SEND,
        /** Received inside the transaction: still on its queue, as delivering, until it commits. */
        RECEIVE,
        /** An operation string this tool does not recognise; shown as the broker wrote it. */
        OTHER
    }

    static Operation operation(String raw)
    {
        String text = raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("send"))
        {
            return Operation.SEND;
        }
        if (text.contains("receive") || text.contains("ack"))
        {
            return Operation.RECEIVE;
        }
        return Operation.OTHER;
    }

    public boolean send()
    {
        return operation == Operation.SEND;
    }

    public boolean receive()
    {
        return operation == Operation.RECEIVE;
    }

    /** "send", "receive", or the broker's own words. */
    public String operationText()
    {
        return switch (operation)
        {
            case SEND -> "send";
            case RECEIVE -> "receive";
            case OTHER -> rawOperation;
        };
    }

    /** The id to show and look up by: the user id when there is one, which is what searches match. */
    public String displayId()
    {
        return userId == null || userId.isEmpty() ? messageId : userId;
    }

    public String timestampText()
    {
        return timestamp > 0 ? TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestamp)) : "";
    }
}
