package com.culberth.tools.artemisbrowser.broker;

/**
 * The broker could not parse a filter ({@code AMQ229020: Invalid filter}), so nothing was searched.
 *
 * <p>
 * Its own type because the page must not read it as an answer. Before Phase 14 P3 it was an ordinary rejected call,
 * worded with the generic advice to check the {@code manage} permission — wrong for a filter, and easy to take for a
 * search that ran and found nothing. Verified on 2.55.0 and 2.57.0: an unterminated literal, {@code ==}, a reserved
 * word as a name and a dotted bare name are all refused this way, while a hyphenated bare name is <em>not</em> — it
 * parses as a subtraction and quietly matches nothing, which no error can reveal.
 */
public class InvalidFilterException extends ManagementRefusal
{

    public InvalidFilterException(String message)
    {
        super(Availability.FAILED, message);
    }

    static boolean recognises(String reason)
    {
        return reason != null && reason.contains("AMQ229020");
    }

    static String explain(String what, String reason)
    {
        return "The broker could not read this filter, so " + what + " searched nothing — this is not a result of"
                + " zero matches (" + reason + "). Filters use Artemis core syntax: text in single quotes with an"
                + " apostrophe doubled ('O''Brien'), a property name that is not a plain identifier in double quotes"
                + " (\"order-id\"), and = rather than ==.";
    }
}
