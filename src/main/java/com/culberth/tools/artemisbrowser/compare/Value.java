package com.culberth.tools.artemisbrowser.compare;

import tools.jackson.databind.JsonNode;

/**
 * One value as a saved snapshot recorded it: a number, a piece of text, or the reason there is none.
 *
 * <p>
 * The file is untrusted and may be from an older build, so every value is read defensively: a field that is not there
 * is "not in this snapshot", a field the snapshot wrote as {@code {"unavailable": ...}} keeps the snapshot's own
 * reason, and a field of a shape this build does not expect is "not readable" — none of them is ever a zero.
 *
 * @param text    the value as text, or null when there is none
 * @param number  the value when it is a whole number, else null
 * @param missing why there is no value, or null when there is one
 * @param detail  the broker's own words for a missing value, when the snapshot recorded them; else empty
 */
public record Value(String text, Long number, String missing, String detail)
{

    static final String NOT_IN_FILE = "not in this snapshot";

    public static Value of(String text)
    {
        return new Value(text, null, null, "");
    }

    public static Value of(long number)
    {
        return new Value(Long.toString(number), number, null, "");
    }

    public static Value missing(String why, String detail)
    {
        return new Value(null, null, why, detail == null ? "" : detail);
    }

    /** Reads one field as the snapshot writer wrote it. */
    static Value from(JsonNode node)
    {
        if (node == null || node.isMissingNode())
        {
            return missing(NOT_IN_FILE, "");
        }
        if (node.isNull())
        {
            return of("none");
        }
        if (node.isIntegralNumber() && node.canConvertToLong())
        {
            return of(node.asLong());
        }
        if (node.isNumber())
        {
            return of(node.asString());
        }
        if (node.isString())
        {
            return of(node.asString());
        }
        if (node.isBoolean())
        {
            return of(Boolean.toString(node.asBoolean()));
        }
        if (node.isObject() && node.path("unavailable").isString())
        {
            return missing(node.path("unavailable").asString(), node.path("detail").asString(""));
        }
        return missing("not a value this build reads", "");
    }

    public boolean known()
    {
        return missing == null;
    }

    public boolean isNumber()
    {
        return number != null;
    }

    /** The value, or its reason after a dash — never blank, never a zero standing in. */
    public String display()
    {
        return known() ? text : "— " + missing;
    }

    /** True only when both sides have a value and the values differ; a missing side is not a change. */
    public boolean differsFrom(Value other)
    {
        return known() && other.known() && !text.equals(other.text);
    }
}
