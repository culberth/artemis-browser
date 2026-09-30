package com.culberth.tools.artemisbrowser.broker;

import tools.jackson.databind.JsonNode;

/**
 * Reads one field of a management listing row — {@code listQueues}, {@code listAddresses} — as a {@link Reading}.
 *
 * <p>
 * Both listings quote every value, so {@code "ringSize":"3"} and {@code "exclusive":"true"}. A field this broker's
 * listing does not carry is {@link Availability#UNSUPPORTED}, and one that is there in a shape nobody verified is
 * {@link Availability#FAILED} — neither becomes a zero or a false, since the pages and diagnose draw conclusions from
 * them.
 */
final class ListingFields
{

    private ListingFields()
    {
    }

    static Reading<Long> number(JsonNode node, String field, String listing)
    {
        JsonNode value = present(node, field);
        if (value == null)
        {
            return notInListing(field, listing);
        }
        try
        {
            return Reading.of(Long.parseLong(value.asText().trim()));
        }
        catch (NumberFormatException e)
        {
            return Reading.failed("'" + value.asText() + "' is not a number");
        }
    }

    static Reading<Boolean> flag(JsonNode node, String field, String listing)
    {
        JsonNode value = present(node, field);
        if (value == null)
        {
            return notInListing(field, listing);
        }
        String text = value.asText().trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))
        {
            return Reading.of(Boolean.parseBoolean(text));
        }
        return Reading.failed("'" + text + "' is not true or false");
    }

    /** Text as given; an empty string is a real value here ("no key"), not a missing one. */
    static Reading<String> text(JsonNode node, String field, String listing)
    {
        JsonNode value = present(node, field);
        return value == null ? notInListing(field, listing) : Reading.of(value.asText());
    }

    private static JsonNode present(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    private static <T> Reading<T> notInListing(String field, String listing)
    {
        return Reading.missing(Availability.UNSUPPORTED, "'" + field + "' is not in this broker's " + listing);
    }
}
