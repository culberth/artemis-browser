package com.culberth.tools.artemisbrowser.broker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a search that is an exact lookup of one message by its ID — the only kind of search that can be answered
 * against in-flight messages too.
 *
 * <p>
 * In-flight messages come back from the broker unfiltered, so checking them against a filter would mean evaluating
 * Artemis's filter language here: a new source of silently wrong answers, declined in Phase 10. An exact ID needs no
 * evaluation, only a string comparison. So only two inputs count: the core filter {@code AMQUserID = 'ID:…'} and
 * nothing else, or a bare {@code ID:…} as copied off a page, which is rewritten to that filter. Verified against
 * 2.44.0: the ID a browse reports, the one the delivering list reports and the consumer's {@code JMSMessageID} are the
 * same string, {@code AMQUserID} matches it only with its {@code ID:} prefix, and {@code JMSMessageID = '…'} silently
 * matches nothing.
 */
public final class MessageIdLookup
{

    private static final Pattern FILTER = Pattern.compile("^\\s*AMQUserID\\s*=\\s*'([^']+)'\\s*$");
    private static final Pattern BARE_ID = Pattern.compile("^\\s*(ID:[^\\s']+)\\s*$");

    private MessageIdLookup()
    {
    }

    /** The message ID being looked up, or null when the input is any other kind of filter. */
    public static String messageId(String input)
    {
        if (input == null)
        {
            return null;
        }
        Matcher filter = FILTER.matcher(input);
        if (filter.matches())
        {
            return filter.group(1);
        }
        Matcher bare = BARE_ID.matcher(input);
        return bare.matches() ? bare.group(1) : null;
    }

    /**
     * The filter to hand the broker: a bare ID becomes {@code AMQUserID = 'ID:…'}, anything else is returned trimmed. A
     * bare ID would otherwise reach the broker as a syntax error.
     */
    public static String filterFor(String input)
    {
        if (input == null)
        {
            return "";
        }
        Matcher bare = BARE_ID.matcher(input);
        return bare.matches() ? "AMQUserID = '" + bare.group(1) + "'" : input.trim();
    }
}
