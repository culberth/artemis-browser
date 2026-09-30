package com.culberth.tools.artemisbrowser.broker;

import com.culberth.tools.artemisbrowser.broker.MessageComparison.Body;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.BodyMode;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.Change;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.Field;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.Line;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares two messages already read: headers, properties, and a text or JSON body. No broker access — everything here
 * works on two {@link MessageDetail}s, and every size it handles is bounded by the limits it is given.
 *
 * <p>
 * Message content is untrusted. Nothing here interprets it beyond parsing JSON into a tree, and every value leaves
 * through {@link #visible} so that control, bidirectional and zero-width characters — which could make two different
 * values look identical, or reorder what is drawn — are shown as escapes.
 */
public final class MessageComparer
{

    /** Unchanged lines kept around each change in a text diff; longer unchanged runs are collapsed. */
    static final int CONTEXT_LINES = 3;

    /**
     * Decimals are read as {@code BigDecimal}: read as doubles, two numbers that differ past the seventeenth digit
     * would compare equal, and the page would say the bodies hold the same values when they do not.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final int maxBodyChars;
    private final long maxAlignCells;
    private final int maxShownLines;
    private final int maxJsonPaths;

    /**
     * @param maxBodyChars  how much of each body is compared; the rest is not, and says so
     * @param maxAlignCells the largest (changed lines left × changed lines right) aligned line by line; past it the
     *                      changed region is shown as replaced rather than aligned
     * @param maxShownLines diff lines drawn at most
     * @param maxJsonPaths  values per JSON body at most; past it the bodies are compared as text
     */
    public MessageComparer(int maxBodyChars, long maxAlignCells, int maxShownLines, int maxJsonPaths)
    {
        this.maxBodyChars = maxBodyChars;
        this.maxAlignCells = maxAlignCells;
        this.maxShownLines = maxShownLines;
        this.maxJsonPaths = maxJsonPaths;
    }

    public List<Field> headers(MessageDetail left, MessageDetail right)
    {
        List<Field> fields = new ArrayList<>();
        fields.add(field("Message ID", left.messageId(), right.messageId()));
        fields.add(field("Correlation ID", left.correlationId(), right.correlationId()));
        fields.add(field("Type", left.type(), right.type()));
        fields.add(field("Destination", left.destination(), right.destination()));
        fields.add(field("Timestamp", blankAsAbsent(left.timestampText()), blankAsAbsent(right.timestampText())));
        fields.add(field("Expires", left.expirationText(), right.expirationText()));
        fields.add(field("Priority", String.valueOf(left.priority()), String.valueOf(right.priority())));
        fields.add(field("Persistent", String.valueOf(left.persistent()), String.valueOf(right.persistent())));
        fields.add(field("Redelivered", String.valueOf(left.redelivered()), String.valueOf(right.redelivered())));
        fields.add(
                field("Delivery count", String.valueOf(left.deliveryCount()), String.valueOf(right.deliveryCount())));
        fields.add(field("Group ID", left.groupId(), right.groupId()));
        fields.add(field("Large message", String.valueOf(left.largeMessage()), String.valueOf(right.largeMessage())));
        return fields;
    }

    /**
     * Every property either side carries, by name. A property present on one side only is reported as such, never as a
     * difference from an empty value; a value of a different type (string {@code "5"}, integer {@code 5}) differs.
     */
    public List<Field> properties(MessageDetail left, MessageDetail right)
    {
        TreeSet<String> names = new TreeSet<>(left.properties().keySet());
        names.addAll(right.properties().keySet());
        List<Field> fields = new ArrayList<>();
        for (String name : names)
        {
            String leftValue = left.properties().get(name);
            String rightValue = right.properties().get(name);
            String leftType = leftValue == null ? null : left.propertyTypes().get(name);
            String rightType = rightValue == null ? null : right.propertyTypes().get(name);
            Change change = change(leftValue, rightValue);
            if (change == Change.SAME && !Objects.equals(leftType, rightType))
            {
                change = Change.DIFFERENT;
            }
            fields.add(new Field(name, leftValue, leftType, rightValue, rightType, change));
        }
        return fields;
    }

    /**
     * The bodies: as JSON when both are whole, text messages holding a JSON object or array; as text lines when both
     * are text; not at all otherwise.
     */
    public Body body(MessageDetail left, MessageDetail right)
    {
        if (!"Text".equals(left.type()) || !"Text".equals(right.type()))
        {
            return new Body(BodyMode.NOT_COMPARED, false,
                    "Bodies are compared only between two text messages. This is " + article(left.type()) + " and "
                            + article(right.type())
                            + " message; open each one to read its body. Headers and properties are compared above.",
                    List.of(), List.of(), List.of(), 0);
        }

        String leftText = left.body() == null ? "" : left.body();
        String rightText = right.body() == null ? "" : right.body();
        List<String> limitations = new ArrayList<>();
        boolean leftCut = left.bodyTruncated() || leftText.length() > maxBodyChars;
        boolean rightCut = right.bodyTruncated() || rightText.length() > maxBodyChars;
        if (leftText.length() > maxBodyChars)
        {
            leftText = leftText.substring(0, maxBodyChars);
        }
        if (rightText.length() > maxBodyChars)
        {
            rightText = rightText.substring(0, maxBodyChars);
        }
        if (leftCut || rightCut)
        {
            limitations.add("Only the first " + Math.min(maxBodyChars, Math.max(leftText.length(), rightText.length()))
                    + " characters of "
                    + (leftCut && rightCut ? "each body" : leftCut ? "the left body" : "the right body")
                    + " were compared. Nothing is known about the rest, so matching text here does not mean"
                    + " matching bodies.");
        }

        if (!leftCut && !rightCut)
        {
            Body json = json(leftText, rightText, limitations);
            if (json != null)
            {
                return json;
            }
        }
        return text(leftText, rightText, limitations, leftCut || rightCut);
    }

    private Body json(String leftText, String rightText, List<String> limitations)
    {
        JsonNode leftTree = parse(leftText);
        JsonNode rightTree = parse(rightText);
        if (leftTree == null || rightTree == null)
        {
            return null;
        }
        Map<String, JsonNode> leftPaths = new LinkedHashMap<>();
        Map<String, JsonNode> rightPaths = new LinkedHashMap<>();
        if (!flatten("$", leftTree, leftPaths) || !flatten("$", rightTree, rightPaths))
        {
            limitations.add("A body has more than " + maxJsonPaths + " JSON values, so both were compared as text.");
            return null;
        }

        TreeSet<String> paths = new TreeSet<>(leftPaths.keySet());
        paths.addAll(rightPaths.keySet());
        List<Field> differences = new ArrayList<>();
        int unchanged = 0;
        for (String path : paths)
        {
            JsonNode leftValue = leftPaths.get(path);
            JsonNode rightValue = rightPaths.get(path);
            if (leftValue != null && leftValue.equals(rightValue))
            {
                unchanged++;
                continue;
            }
            differences.add(new Field(path, leftValue == null ? null : leftValue.toString(), kind(leftValue),
                    rightValue == null ? null : rightValue.toString(), kind(rightValue),
                    change(leftValue == null ? null : "", rightValue == null ? null : "", false)));
        }
        boolean identical = differences.isEmpty();
        String explanation = identical
                ? (leftText.equals(rightText) ? "Both bodies are the same JSON, character for character."
                        : "Both bodies hold the same JSON values; they differ only in layout or key order.")
                : "Both bodies are JSON, so they are compared value by value. A path missing on one side is"
                        + " shown as missing, which is not the same as null or an empty string.";
        if (differences.size() > maxShownLines)
        {
            limitations.add((differences.size() - maxShownLines) + " more differing values are not shown.");
            differences = differences.subList(0, maxShownLines);
        }
        return new Body(BodyMode.JSON, identical, explanation, limitations, List.of(), List.copyOf(differences),
                unchanged);
    }

    private JsonNode parse(String text)
    {
        String trimmed = text.strip();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("[")))
        {
            return null;
        }
        try
        {
            JsonNode tree = objectMapper.readTree(trimmed);
            return tree != null && (tree.isObject() || tree.isArray()) ? tree : null;
        }
        catch (JacksonException e)
        {
            return null;
        }
    }

    /** Every leaf value by path; false when there are more than {@code maxJsonPaths}. */
    private boolean flatten(String path, JsonNode node, Map<String, JsonNode> into)
    {
        if (node.isObject() && !node.isEmpty())
        {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext();)
            {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!flatten(path + key(entry.getKey()), entry.getValue(), into))
                {
                    return false;
                }
            }
            return true;
        }
        if (node.isArray() && !node.isEmpty())
        {
            for (int i = 0; i < node.size(); i++)
            {
                if (!flatten(path + "[" + i + "]", node.get(i), into))
                {
                    return false;
                }
            }
            return true;
        }
        into.put(path, node);
        return into.size() <= maxJsonPaths;
    }

    private static String key(String name)
    {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") ? "." + name
                : "[\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    }

    private static String kind(JsonNode node)
    {
        if (node == null)
        {
            return null;
        }
        if (node.isString())
        {
            return "string";
        }
        if (node.isNumber())
        {
            return "number";
        }
        if (node.isBoolean())
        {
            return "boolean";
        }
        if (node.isNull())
        {
            // Drawn as a bare null, which the quotes on a string "null" already tell apart; a badge would repeat it.
            return null;
        }
        return node.isArray() ? "empty array" : "empty object";
    }

    private Body text(String leftText, String rightText, List<String> limitations, boolean cut)
    {
        if (leftText.equals(rightText))
        {
            return new Body(BodyMode.TEXT, true,
                    cut ? "The compared text is identical." : "Both bodies are the same text, character for character.",
                    limitations, List.of(), List.of(), 0);
        }
        String[] a = leftText.split("\n", -1);
        String[] b = rightText.split("\n", -1);

        int start = 0;
        while (start < a.length && start < b.length && a[start].equals(b[start]))
        {
            start++;
        }
        int endA = a.length;
        int endB = b.length;
        while (endA > start && endB > start && a[endA - 1].equals(b[endB - 1]))
        {
            endA--;
            endB--;
        }

        List<Line> all = new ArrayList<>();
        for (int i = 0; i < start; i++)
        {
            all.add(new Line("same", i + 1, i + 1, a[i], 0));
        }
        long cells = (long) (endA - start) * (endB - start);
        if (cells <= maxAlignCells)
        {
            align(a, start, endA, b, start, endB, all);
        }
        else
        {
            limitations.add("The changed region is " + (endA - start) + " lines on the left and " + (endB - start)
                    + " on the right — too large to align line by line — so it is shown as removed and then added,"
                    + " even where some lines match.");
            for (int i = start; i < endA; i++)
            {
                all.add(new Line("removed", i + 1, 0, a[i], 0));
            }
            for (int j = start; j < endB; j++)
            {
                all.add(new Line("added", 0, j + 1, b[j], 0));
            }
        }
        for (int i = endA, j = endB; i < a.length; i++, j++)
        {
            all.add(new Line("same", i + 1, j + 1, a[i], 0));
        }

        List<Line> shown = collapse(all);
        if (shown.size() > maxShownLines)
        {
            limitations.add("The diff is " + shown.size() + " lines; only the first " + maxShownLines + " are shown.");
            shown = shown.subList(0, maxShownLines);
        }
        String explanation = "Compared line by line. Lines are split at line feeds only, so a carriage return shows"
                + " as \\r at the end of a line.";
        return new Body(BodyMode.TEXT, false, explanation, limitations, List.copyOf(shown), List.of(), 0);
    }

    /** A longest-common-subsequence alignment of the changed region, which the caller has bounded. */
    private void align(String[] a, int fromA, int toA, String[] b, int fromB, int toB, List<Line> into)
    {
        int n = toA - fromA;
        int m = toB - fromB;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
        {
            for (int j = m - 1; j >= 0; j--)
            {
                lcs[i][j] = a[fromA + i].equals(b[fromB + j]) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n || j < m)
        {
            if (i < n && j < m && a[fromA + i].equals(b[fromB + j]))
            {
                into.add(new Line("same", fromA + i + 1, fromB + j + 1, a[fromA + i], 0));
                i++;
                j++;
            }
            else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]))
            {
                // Removals before additions on a tie, the way a reader expects a replaced line to read.
                into.add(new Line("removed", fromA + i + 1, 0, a[fromA + i], 0));
                i++;
            }
            else
            {
                into.add(new Line("added", 0, fromB + j + 1, b[fromB + j], 0));
                j++;
            }
        }
    }

    /** Keeps {@link #CONTEXT_LINES} unchanged lines around each change and replaces longer runs with one skip line. */
    private static List<Line> collapse(List<Line> all)
    {
        boolean[] keep = new boolean[all.size()];
        for (int k = 0; k < all.size(); k++)
        {
            if (!"same".equals(all.get(k).op()))
            {
                for (int c = Math.max(0, k - CONTEXT_LINES); c <= Math.min(all.size() - 1, k + CONTEXT_LINES); c++)
                {
                    keep[c] = true;
                }
            }
        }
        List<Line> shown = new ArrayList<>();
        int skipped = 0;
        for (int k = 0; k < all.size(); k++)
        {
            if (keep[k])
            {
                if (skipped > 0)
                {
                    shown.add(new Line("skip", 0, 0, "", skipped));
                    skipped = 0;
                }
                shown.add(all.get(k));
            }
            else
            {
                skipped++;
            }
        }
        if (skipped > 0)
        {
            shown.add(new Line("skip", 0, 0, "", skipped));
        }
        return shown;
    }

    private static Field field(String name, String left, String right)
    {
        return new Field(name, left, null, right, null, change(left, right));
    }

    static Change change(String left, String right)
    {
        return change(left, right, Objects.equals(left, right));
    }

    private static Change change(String left, String right, boolean equal)
    {
        if (left == null && right != null)
        {
            return Change.ONLY_RIGHT;
        }
        if (right == null && left != null)
        {
            return Change.ONLY_LEFT;
        }
        return equal ? Change.SAME : Change.DIFFERENT;
    }

    private static String blankAsAbsent(String value)
    {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String article(String type)
    {
        String name = type == null ? "unknown" : type.toLowerCase();
        return ("aeiou".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    /** A value as drawn: absent and empty named in words, anything else made {@link #visible}. */
    public static String shown(String value)
    {
        if (value == null)
        {
            return "(not set)";
        }
        return value.isEmpty() ? "(empty)" : visible(value);
    }

    /**
     * Untrusted text made safe to look at, not just safe to put in HTML (the template escapes it for that): control
     * characters other than tab, bidirectional overrides and isolates, zero-width characters and the byte-order mark
     * are written as {@code \\uXXXX} escapes, so that two values that differ never look the same and nothing reorders
     * the page around it.
     */
    public static String visible(String text)
    {
        if (text == null)
        {
            return "";
        }
        StringBuilder builder = null;
        for (int k = 0; k < text.length(); k++)
        {
            char c = text.charAt(k);
            String escape = escape(c);
            if (escape != null && builder == null)
            {
                builder = new StringBuilder(text.length() + 16).append(text, 0, k);
            }
            if (builder != null)
            {
                builder.append(escape != null ? escape : String.valueOf(c));
            }
        }
        return builder == null ? text : builder.toString();
    }

    private static String escape(char c)
    {
        if (c == '\r')
        {
            return "\\r";
        }
        if (c == '\n')
        {
            return "\\n";
        }
        boolean control = (c < 0x20 && c != '\t') || (c >= 0x7F && c <= 0x9F);
        boolean bidi = (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069) || c == 0x200E || c == 0x200F
                || c == 0x061C;
        boolean invisible = (c >= 0x200B && c <= 0x200D) || c == 0x2060 || c == 0xFEFF || c == 0x00AD;
        return control || bidi || invisible ? String.format("\\u%04X", (int) c) : null;
    }
}
