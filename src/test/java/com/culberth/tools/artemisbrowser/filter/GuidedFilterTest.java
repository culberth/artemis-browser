package com.culberth.tools.artemisbrowser.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The expressions the builder writes. {@code GuidedFilterIT} runs the same shapes against both supported brokers; this
 * pins the text, and every way a request can be refused.
 */
class GuidedFilterTest
{

    private static GuidedFilter.Result property(String name, String type, String operator, String value)
    {
        return GuidedFilter.build(new GuidedFilter.Request(
                List.of(new GuidedFilter.Condition(name, type, operator, value)), "", "", "", "", "UTC", "ANY"));
    }

    private static GuidedFilter.Result headers(String min, String max, String from, String to, String zone,
            String durability)
    {
        return GuidedFilter.build(new GuidedFilter.Request(List.of(), min, max, from, to, zone, durability));
    }

    @Test
    @DisplayName("nothing filled in writes nothing, and is not an error")
    void emptyRequest()
    {
        GuidedFilter.Result result = headers("", "", "", "", "UTC", "ANY");
        assertTrue(result.empty());
        assertFalse(result.ok());
        assertEquals("", result.expression());
    }

    @Test
    @DisplayName("text is quoted with an apostrophe doubled, and a backslash left alone")
    void textLiterals()
    {
        assertEquals("customer = 'O''Brien'", property("customer", "STRING", "EQUALS", "O'Brien").expression());
        assertEquals("path = 'C:\\temp'", property("path", "STRING", "EQUALS", "C:\\temp").expression());
        assertEquals("name = 'Zoë 50%_x'", property("name", "STRING", "EQUALS", "Zoë 50%_x").expression());
        assertEquals("region <> 'eu'", property("region", "STRING", "NOT_EQUALS", "eu").expression());
    }

    @Test
    @DisplayName("an empty text value is a real value: the empty string")
    void emptyText()
    {
        assertEquals("region = ''", property("region", "STRING", "EQUALS", "").expression());
    }

    @Test
    @DisplayName("starts with and contains escape the value's own wildcards")
    void likePatterns()
    {
        assertEquals("name LIKE 'Zo%' ESCAPE '\\'", property("name", "STRING", "STARTS_WITH", "Zo").expression());
        assertEquals("name LIKE '%50\\%\\_x%' ESCAPE '\\'",
                property("name", "STRING", "CONTAINS", "50%_x").expression());
        assertEquals("path LIKE 'a\\\\b%' ESCAPE '\\'", property("path", "STRING", "STARTS_WITH", "a\\b").expression());
    }

    @Test
    @DisplayName("numbers and booleans are written bare, and checked")
    void typedLiterals()
    {
        assertEquals("count = 5", property("count", "INTEGER", "EQUALS", " 5 ").expression());
        assertEquals("count >= -3", property("count", "INTEGER", "GREATER_OR_EQUAL", "-3").expression());
        assertEquals("big = 10000000002", property("big", "INTEGER", "EQUALS", "10000000002").expression());
        assertEquals("ratio < 1.5", property("ratio", "DECIMAL", "LESS", "1.5").expression());
        assertEquals("ratio = 1000", property("ratio", "DECIMAL", "EQUALS", "1E3").expression());
        assertEquals("flag = TRUE", property("flag", "BOOLEAN", "EQUALS", "True").expression());
        assertEquals("flag <> FALSE", property("flag", "BOOLEAN", "NOT_EQUALS", "false").expression());
    }

    @Test
    @DisplayName("a value that is not of its type is refused, not guessed at")
    void wrongValues()
    {
        assertTrue(property("count", "INTEGER", "EQUALS", "five").errors().get(0).contains("not a whole number"));
        assertTrue(property("count", "INTEGER", "EQUALS", "1.5").errors().get(0).contains("not a whole number"));
        assertTrue(property("ratio", "DECIMAL", "EQUALS", "").errors().get(0).contains("not a decimal"));
        assertTrue(property("flag", "BOOLEAN", "EQUALS", "yes").errors().get(0).contains("not a true / false"));
        assertEquals("", property("count", "INTEGER", "EQUALS", "five").expression());
    }

    @Test
    @DisplayName("comparisons that do not suit a type are refused")
    void wrongOperators()
    {
        assertTrue(property("region", "STRING", "GREATER", "eu").errors().get(0).contains("needs a number"));
        assertTrue(property("flag", "BOOLEAN", "LESS", "true").errors().get(0).contains("needs a number"));
        assertTrue(property("count", "INTEGER", "CONTAINS", "5").errors().get(0).contains("compares text"));
        assertTrue(property("count", "BANANA", "EQUALS", "5").errors().get(0).contains("unknown type"));
        assertTrue(property("count", "INTEGER", "ROUGHLY", "5").errors().get(0).contains("unknown comparison"));
    }

    @Test
    @DisplayName("is set and is not set need no value")
    void presence()
    {
        assertEquals("region IS NOT NULL", property("region", "STRING", "PRESENT", "").expression());
        assertEquals("region IS NULL", property("region", "INTEGER", "ABSENT", "ignored").expression());
    }

    @Test
    @DisplayName("a name that is not a plain identifier, or is a reserved word, is double-quoted")
    void quotedNames()
    {
        assertEquals("\"order-id\" = 'A-1'", property("order-id", "STRING", "EQUALS", "A-1").expression());
        assertEquals("\"my.dotted\" = 'd1'", property("my.dotted", "STRING", "EQUALS", "d1").expression());
        assertEquals("\"2fa\" = TRUE", property("2fa", "BOOLEAN", "EQUALS", "true").expression());
        assertEquals("\"and\" = 1", property("and", "INTEGER", "EQUALS", "1").expression());
        assertEquals("\"a b\" = 1", property("a b", "INTEGER", "EQUALS", "1").expression());
        assertEquals("_under$score = 1", property("_under$score", "INTEGER", "EQUALS", "1").expression());
        assertTrue(property("say \"hi\"", "STRING", "EQUALS", "x").errors().get(0).contains("double quote"));
    }

    @Test
    @DisplayName("a value with no name is an error; a row with neither is skipped")
    void blankRows()
    {
        assertTrue(property(" ", "STRING", "EQUALS", "eu").errors().get(0).contains("no property name"));
        assertTrue(property("", "STRING", "PRESENT", "").empty());
    }

    @Test
    @DisplayName("notes say what a clause does not show: numbers as text, 'is not' and absent properties, AMQ names")
    void notes()
    {
        assertTrue(property("count", "STRING", "EQUALS", "5").notes().get(0).contains("compared as text"));
        assertTrue(property("region", "STRING", "NOT_EQUALS", "eu").notes().get(0).contains("do not have region"));
        assertTrue(property("AMQCustom", "STRING", "EQUALS", "x").notes().get(0).contains("message headers"));
        assertTrue(property("region", "STRING", "EQUALS", "eu").notes().isEmpty());
    }

    @Test
    @DisplayName("priority: one value, a range, or one end")
    void priority()
    {
        assertEquals("AMQPriority = 4", headers("4", "4", "", "", "UTC", "ANY").expression());
        assertEquals("AMQPriority BETWEEN 4 AND 7", headers("4", "7", "", "", "UTC", "ANY").expression());
        assertEquals("AMQPriority >= 5", headers("5", "", "", "", "UTC", "ANY").expression());
        assertEquals("AMQPriority <= 2", headers("", "2", "", "", "UTC", "ANY").expression());
        assertTrue(headers("7", "4", "", "", "UTC", "ANY").errors().get(0).contains("above the highest"));
        assertTrue(headers("10", "", "", "", "UTC", "ANY").errors().get(0).contains("0 to 9"));
        assertTrue(headers("x", "", "", "", "UTC", "ANY").errors().get(0).contains("whole number"));
    }

    @Test
    @DisplayName("durability is the broker's own two words")
    void durability()
    {
        assertEquals("AMQDurable = 'DURABLE'", headers("", "", "", "", "UTC", "DURABLE").expression());
        assertEquals("AMQDurable = 'NON_DURABLE'", headers("", "", "", "", "UTC", "non_durable").expression());
        assertTrue(headers("", "", "", "", "UTC", "sometimes").errors().get(0).contains("unknown choice"));
    }

    @Test
    @DisplayName("sent times are read in the named zone, from included and to excluded")
    void sentRange()
    {
        GuidedFilter.Result utc = headers("", "", "2026-09-30T12:00", "2026-09-30T13:00:30", "UTC", "ANY");
        long from = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();
        long to = Instant.parse("2026-09-30T13:00:30Z").toEpochMilli();
        assertEquals("AMQTimestamp >= " + from + " AND AMQTimestamp < " + to, utc.expression());

        GuidedFilter.Result london = headers("", "", "2026-09-30T12:00", "", "Europe/London", "ANY");
        assertEquals("AMQTimestamp >= " + Instant.parse("2026-09-30T11:00:00Z").toEpochMilli(), london.expression());
        assertTrue(london.clauses().get(0).described().contains("+01:00"), london.clauses().get(0).described());

        assertEquals("AMQTimestamp < " + Instant.parse("2026-09-30T12:00:00.250Z").toEpochMilli(),
                headers("", "", "", "2026-09-30T12:00:00.250", "UTC", "ANY").expression());
    }

    @Test
    @DisplayName("a local time the clocks skipped or repeated is resolved, and says how")
    void daylightSaving()
    {
        // Europe/London: 2026-03-29 01:00 → 02:00 (gap); 2026-10-25 02:00 → 01:00 (overlap).
        GuidedFilter.Result gap = headers("", "", "2026-03-29T01:30", "", "Europe/London", "ANY");
        assertEquals("AMQTimestamp >= " + Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(), gap.expression());
        assertTrue(gap.notes().stream().anyMatch(n -> n.contains("does not exist")), gap.notes().toString());

        GuidedFilter.Result overlap = headers("", "", "2026-10-25T01:30", "", "Europe/London", "ANY");
        assertEquals("AMQTimestamp >= " + Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), overlap.expression());
        assertTrue(overlap.notes().stream().anyMatch(n -> n.contains("happened twice")), overlap.notes().toString());
    }

    @Test
    @DisplayName("bad times and zones are refused")
    void badTimes()
    {
        assertTrue(headers("", "", "yesterday", "", "UTC", "ANY").errors().get(0).contains("not a date and time"));
        assertTrue(headers("", "", "2026-09-30T12:00", "", "Mars/Olympus", "ANY").errors().get(0)
                .contains("not a time zone"));
        assertTrue(headers("", "", "2026-09-30T12:00", "2026-09-30T12:00", "UTC", "ANY").errors().get(0)
                .contains("before 'to'"));
    }

    @Test
    @DisplayName("parts are joined with AND, each one listed beside its text")
    void combined()
    {
        GuidedFilter.Result result = GuidedFilter.build(new GuidedFilter.Request(
                List.of(new GuidedFilter.Condition("region", "STRING", "EQUALS", "eu"),
                        new GuidedFilter.Condition("", "", "", ""),
                        new GuidedFilter.Condition("attempt", "INTEGER", "GREATER", "2")),
                "5", "", "", "", "UTC", "DURABLE"));
        assertEquals("region = 'eu' AND attempt > 2 AND AMQPriority >= 5 AND AMQDurable = 'DURABLE'",
                result.expression());
        assertEquals(4, result.clauses().size());
        assertEquals("attempt > 2", result.clauses().get(1).text());
    }

    @Test
    @DisplayName("any error means no expression at all, never a partial one")
    void errorsWriteNothing()
    {
        GuidedFilter.Result result = GuidedFilter
                .build(new GuidedFilter.Request(List.of(new GuidedFilter.Condition("region", "STRING", "EQUALS", "eu")),
                        "12", "", "", "", "UTC", "ANY"));
        assertFalse(result.ok());
        assertEquals("", result.expression());
    }
}
