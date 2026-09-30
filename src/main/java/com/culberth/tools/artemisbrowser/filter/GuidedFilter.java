package com.culberth.tools.artemisbrowser.filter;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Writes an Artemis <em>core</em> filter from a handful of form fields, so a common search needs no knowledge of the
 * syntax.
 *
 * <p>
 * It only writes the expression. The broker evaluates it, as it evaluates one typed by hand: there is no local selector
 * engine here, and nothing decides locally whether a message matches. What this class owns is the part the broker
 * cannot help with — an expression that is valid and means something other than what was meant matches nothing, or the
 * wrong thing, without an error. Each rule below was checked against 2.55.0 and 2.57.0 (see {@code .claude/memory.md},
 * <em>Core filter syntax</em>) before it was written:
 *
 * <ul>
 * <li>A string literal doubles an apostrophe ({@code 'O''Brien'}); a backslash is an ordinary character.</li>
 * <li>Types do not convert. A property sent as the string {@code "5"} does not equal {@code 5}, a boolean does not
 * equal {@code 'true'}, and an integer property does equal {@code 2.0}. So each condition names its type.</li>
 * <li>A property name that is not a plain identifier needs double quotes. {@code my-prop = 'x'} is valid and reads as
 * {@code my} minus {@code prop}, matching nothing; {@code my.prop = 'x'} is rejected.</li>
 * <li>{@code AMQTimestamp} is epoch milliseconds; {@code AMQDurable} is {@code 'DURABLE'} or
 * {@code 'NON_DURABLE'}.</li>
 * </ul>
 */
public final class GuidedFilter
{

    /** Words the filter grammar reserves; a property named one of these has to be quoted to be read as a name. */
    private static final Set<String> RESERVED = Set.of("AND", "OR", "NOT", "BETWEEN", "LIKE", "IN", "IS", "NULL",
            "TRUE", "FALSE", "ESCAPE");
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx",
            Locale.ROOT);
    /** The escape character for LIKE patterns. Verified: a backslash in a core string literal is itself. */
    private static final char LIKE_ESCAPE = '\\';

    private GuidedFilter()
    {
    }

    /** What a property value is compared as. The broker compares types strictly, so this is not a formality. */
    public enum Type
    {
        STRING("text"), INTEGER("whole number"), DECIMAL("decimal number"), BOOLEAN("true / false");

        private final String label;

        Type(String label)
        {
            this.label = label;
        }

        public String label()
        {
            return label;
        }

        static Type parse(String value)
        {
            if (value == null || value.isBlank())
            {
                return STRING;
            }
            try
            {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException e)
            {
                return null;
            }
        }
    }

    /** How a property is compared. Not every operator suits every type; {@link #build} says which do not. */
    public enum Operator
    {
        EQUALS("is", "="), NOT_EQUALS("is not", "<>"), LESS("less than", "<"), LESS_OR_EQUAL("at most", "<="),
        GREATER("greater than", ">"), GREATER_OR_EQUAL("at least", ">="), STARTS_WITH("starts with", "LIKE"),
        CONTAINS("contains", "LIKE"), PRESENT("is set", "IS NOT NULL"), ABSENT("is not set", "IS NULL");

        private final String label;
        private final String symbol;

        Operator(String label, String symbol)
        {
            this.label = label;
            this.symbol = symbol;
        }

        public String label()
        {
            return label;
        }

        boolean needsValue()
        {
            return this != PRESENT && this != ABSENT;
        }

        boolean ordering()
        {
            return this == LESS || this == LESS_OR_EQUAL || this == GREATER || this == GREATER_OR_EQUAL;
        }

        boolean pattern()
        {
            return this == STARTS_WITH || this == CONTAINS;
        }

        static Operator parse(String value)
        {
            if (value == null || value.isBlank())
            {
                return EQUALS;
            }
            try
            {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException e)
            {
                return null;
            }
        }
    }

    /**
     * One property condition as the form sent it. Every field is raw text; nothing is trusted to be a valid type or
     * operator until {@link #build} has checked it.
     */
    public record Condition(String name, String type, String operator, String value)
    {

        boolean blank()
        {
            return isBlank(name) && isBlank(value);
        }
    }

    /**
     * Everything the form can say. Blank fields are "any": a request with nothing filled in writes no expression.
     *
     * @param sentFrom a local date-time ({@code 2026-09-30T14:00}, seconds optional), read in {@code zone}; inclusive
     * @param sentTo   the same, exclusive, so two adjacent ranges never both hold a message sent on the boundary
     * @param zone     an IANA zone id, or {@code UTC}; the date-times above mean nothing without it
     */
    public record Request(List<Condition> conditions, String priorityMin, String priorityMax, String sentFrom,
            String sentTo, String zone, String durability)
    {

        public Request
        {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    /**
     * The written expression, or why none could be written.
     *
     * @param expression the core filter, or empty when nothing was asked for or something was wrong
     * @param clauses    each part in words beside the text it became, so the expression can be checked by eye
     * @param notes      things true of the result that the expression does not show — how a zone was resolved, what a
     *                   negative comparison leaves out
     * @param errors     what stopped it being written; when present there is no expression
     */
    public record Result(String expression, List<Clause> clauses, List<String> notes, List<String> errors)
    {

        public boolean ok()
        {
            return errors.isEmpty() && !expression.isEmpty();
        }

        public boolean empty()
        {
            return errors.isEmpty() && expression.isEmpty();
        }
    }

    /** One condition in words, and the text it was written as. */
    public record Clause(String described, String text)
    {
    }

    public static Result build(Request request)
    {
        List<Clause> clauses = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        int row = 0;
        for (Condition condition : request.conditions())
        {
            row++;
            if (condition == null || condition.blank())
            {
                continue;
            }
            property(row, condition, clauses, notes, errors);
        }
        priority(request.priorityMin(), request.priorityMax(), clauses, errors);
        sent(request.sentFrom(), request.sentTo(), request.zone(), clauses, notes, errors);
        durability(request.durability(), clauses, errors);

        if (!errors.isEmpty())
        {
            return new Result("", List.copyOf(clauses), List.copyOf(notes), List.copyOf(errors));
        }
        String expression = String.join(" AND ", clauses.stream().map(Clause::text).toList());
        return new Result(expression, List.copyOf(clauses), List.copyOf(notes), List.of());
    }

    private static void property(int row, Condition condition, List<Clause> clauses, List<String> notes,
            List<String> errors)
    {
        String where = "Condition " + row + ": ";
        String name = condition.name() == null ? "" : condition.name().trim();
        Type type = Type.parse(condition.type());
        Operator operator = Operator.parse(condition.operator());
        if (name.isEmpty())
        {
            errors.add(where + "a value was given with no property name.");
            return;
        }
        if (type == null)
        {
            errors.add(where + "unknown type '" + condition.type() + "'.");
            return;
        }
        if (operator == null)
        {
            errors.add(where + "unknown comparison '" + condition.operator() + "'.");
            return;
        }
        String identifier = identifier(name);
        if (identifier == null)
        {
            errors.add(where + "the property name " + name + " contains a double quote, which this form cannot write."
                    + " Type the filter by hand below.");
            return;
        }
        if (name.startsWith("AMQ"))
        {
            notes.add(where + "names beginning AMQ are how core filters refer to message headers (AMQPriority,"
                    + " AMQTimestamp and so on), so " + name + " may be read as a header rather than a property.");
        }

        if (!operator.needsValue())
        {
            clauses.add(new Clause(name + " " + operator.label(), identifier + " " + operator.symbol));
            return;
        }
        String raw = condition.value() == null ? "" : condition.value();
        if (operator.pattern() && type != Type.STRING)
        {
            errors.add(
                    where + "'" + operator.label() + "' compares text; " + name + " is set as a " + type.label() + ".");
            return;
        }
        if (operator.ordering() && (type == Type.STRING || type == Type.BOOLEAN))
        {
            errors.add(where + "'" + operator.label() + "' needs a number; for " + type.label()
                    + " use 'is' or 'is not'.");
            return;
        }
        String literal = switch (type)
        {
            case STRING -> operator.pattern() ? likePattern(raw, operator) : quote(raw);
            case INTEGER -> integer(raw);
            case DECIMAL -> decimal(raw);
            case BOOLEAN -> bool(raw);
        };
        if (literal == null)
        {
            errors.add(where + "'" + raw + "' is not a " + type.label() + ".");
            return;
        }
        String text = operator.pattern() ? identifier + " LIKE " + literal + " ESCAPE '" + LIKE_ESCAPE + "'"
                : identifier + " " + operator.symbol + " " + literal;
        clauses.add(new Clause(name + " (" + type.label() + ") " + operator.label() + " " + shown(raw, type), text));
        if (operator == Operator.NOT_EQUALS)
        {
            notes.add(where + "'is not' leaves out messages that do not have " + name + " at all; add a condition"
                    + " '" + name + " is not set' in a separate search to see those.");
        }
        if (type == Type.STRING && !operator.pattern() && looksNumeric(raw))
        {
            notes.add(where + "compared as text. A sender that set " + name + " as a number is not matched by '" + raw
                    + "' as text; choose a number type for that.");
        }
    }

    /**
     * A property name as the grammar reads it: bare when it is a plain identifier and not a reserved word, otherwise in
     * double quotes (verified for a hyphen and a dot). Null when it contains a double quote, which has no verified
     * escape.
     */
    static String identifier(String name)
    {
        if (plainIdentifier(name))
        {
            return name;
        }
        if (name.indexOf('"') >= 0)
        {
            return null;
        }
        return "\"" + name + "\"";
    }

    private static boolean plainIdentifier(String name)
    {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))
                || RESERVED.contains(name.toUpperCase(Locale.ROOT)))
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            if (!Character.isJavaIdentifierPart(name.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }

    /** A core string literal: in apostrophes, an apostrophe doubled. Nothing else is escaped, nor needs to be. */
    static String quote(String value)
    {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * The value found literally: its own {@code %}, {@code _} and escape character escaped, then the wildcard added.
     */
    private static String likePattern(String value, Operator operator)
    {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray())
        {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE)
            {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        String pattern = operator == Operator.CONTAINS ? "%" + escaped + "%" : escaped + "%";
        return quote(pattern);
    }

    private static String integer(String raw)
    {
        try
        {
            return Long.toString(Long.parseLong(raw.trim()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static String decimal(String raw)
    {
        try
        {
            return new BigDecimal(raw.trim()).toPlainString();
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static String bool(String raw)
    {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value)
        {
            case "true" -> "TRUE";
            case "false" -> "FALSE";
            default -> null;
        };
    }

    private static String shown(String raw, Type type)
    {
        return type == Type.STRING ? "\"" + raw + "\"" : raw.trim();
    }

    private static boolean looksNumeric(String raw)
    {
        return decimal(raw) != null;
    }

    private static void priority(String minText, String maxText, List<Clause> clauses, List<String> errors)
    {
        Integer min = priorityValue(minText, "lowest", errors);
        Integer max = priorityValue(maxText, "highest", errors);
        if (min == null && max == null)
        {
            return;
        }
        if (min != null && max != null && min > max)
        {
            errors.add("Priority: the lowest (" + min + ") is above the highest (" + max + ").");
            return;
        }
        if (min != null && min.equals(max))
        {
            clauses.add(new Clause("priority " + min, "AMQPriority = " + min));
        }
        else if (min != null && max != null)
        {
            clauses.add(new Clause("priority " + min + " to " + max, "AMQPriority BETWEEN " + min + " AND " + max));
        }
        else if (min != null)
        {
            clauses.add(new Clause("priority " + min + " or higher", "AMQPriority >= " + min));
        }
        else
        {
            clauses.add(new Clause("priority " + max + " or lower", "AMQPriority <= " + max));
        }
    }

    private static Integer priorityValue(String text, String which, List<String> errors)
    {
        if (isBlank(text))
        {
            return null;
        }
        try
        {
            int value = Integer.parseInt(text.trim());
            if (value < 0 || value > 9)
            {
                errors.add("Priority: the " + which + " must be 0 to 9, not " + value + ".");
                return null;
            }
            return value;
        }
        catch (NumberFormatException e)
        {
            errors.add("Priority: the " + which + " must be a whole number from 0 to 9, not '" + text + "'.");
            return null;
        }
    }

    /**
     * Sent between two local times in a named zone. The zone is always written into the notes with the instant each end
     * became, because the same wall-clock time is a different moment in every zone, and twice a year a local time is
     * ambiguous or does not exist at all.
     */
    private static void sent(String fromText, String toText, String zoneText, List<Clause> clauses, List<String> notes,
            List<String> errors)
    {
        if (isBlank(fromText) && isBlank(toText))
        {
            return;
        }
        ZoneId zone;
        try
        {
            zone = isBlank(zoneText) ? ZoneId.systemDefault() : ZoneId.of(zoneText.trim());
        }
        catch (DateTimeException e)
        {
            errors.add("Sent: '" + zoneText + "' is not a time zone. Use an IANA name such as Europe/London, or UTC.");
            return;
        }
        Instant from = instant(fromText, zone, "from", notes, errors);
        Instant to = instant(toText, zone, "to", notes, errors);
        if (from == null && to == null)
        {
            return;
        }
        if (from != null && to != null && !from.isBefore(to))
        {
            errors.add("Sent: 'from' must be before 'to'.");
            return;
        }
        if (from != null)
        {
            clauses.add(new Clause("sent at or after " + SHOWN.format(from.atZone(zone)) + " (" + zone.getId() + ")",
                    "AMQTimestamp >= " + from.toEpochMilli()));
        }
        if (to != null)
        {
            clauses.add(new Clause("sent before " + SHOWN.format(to.atZone(zone)) + " (" + zone.getId() + ")",
                    "AMQTimestamp < " + to.toEpochMilli()));
        }
        notes.add("Sent is the time the sender stamped on the message (AMQTimestamp), in milliseconds since"
                + " 1970-01-01 UTC. 'From' is included and 'to' is not. A sender that turned timestamps off"
                + " stamps 0, which no range here includes.");
    }

    private static Instant instant(String text, ZoneId zone, String which, List<String> notes, List<String> errors)
    {
        if (isBlank(text))
        {
            return null;
        }
        LocalDateTime local;
        try
        {
            local = LocalDateTime.parse(text.trim());
        }
        catch (DateTimeParseException e)
        {
            errors.add("Sent: '" + text + "' is not a date and time (" + which + "). Use 2026-09-30T14:00 or"
                    + " 2026-09-30T14:00:05.");
            return null;
        }
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        ZonedDateTime resolved = ZonedDateTime.ofLocal(local, zone, null);
        if (offsets.isEmpty())
        {
            notes.add("Sent " + which + ": " + local + " does not exist in " + zone.getId()
                    + " (the clocks went forward); read as " + SHOWN.format(resolved) + ".");
        }
        else if (offsets.size() > 1)
        {
            notes.add("Sent " + which + ": " + local + " happened twice in " + zone.getId()
                    + " (the clocks went back); read as the first, " + SHOWN.format(resolved) + ".");
        }
        return resolved.toInstant();
    }

    private static void durability(String value, List<Clause> clauses, List<String> errors)
    {
        if (isBlank(value) || "ANY".equalsIgnoreCase(value.trim()))
        {
            return;
        }
        switch (value.trim().toUpperCase(Locale.ROOT))
        {
            case "DURABLE" -> clauses.add(new Clause("durable (persistent)", "AMQDurable = 'DURABLE'"));
            case "NON_DURABLE" -> clauses.add(new Clause("not durable", "AMQDurable = 'NON_DURABLE'"));
            default -> errors.add("Durability: unknown choice '" + value + "'.");
        }
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }
}
