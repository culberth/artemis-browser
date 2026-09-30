package com.culberth.tools.artemisbrowser.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.culberth.tools.artemisbrowser.broker.MessageComparison.Body;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.BodyMode;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.Change;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.Field;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.Line;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageComparerTest
{

    private final MessageComparer comparer = new MessageComparer(10_000, 1_000_000, 2_000, 5_000);

    // ---------------------------------------------------------------- headers

    @Test
    @DisplayName("headers: an absent correlation id is not the same as an empty one")
    void absentHeaderIsNotEmpty()
    {
        List<Field> headers = comparer.headers(text("ID:1", "a").correlation(null).build(),
                text("ID:2", "a").correlation("").build());

        Field correlation = field(headers, "Correlation ID");
        assertThat(correlation.change()).isEqualTo(Change.ONLY_RIGHT);
        assertThat(correlation.leftShown()).isEqualTo("(not set)");
        assertThat(correlation.rightShown()).isEqualTo("(empty)");
        assertThat(field(headers, "Message ID").change()).isEqualTo(Change.DIFFERENT);
        assertThat(field(headers, "Priority").change()).isEqualTo(Change.SAME);
    }

    @Test
    @DisplayName("headers: priority, delivery count and group are compared")
    void headerDifferences()
    {
        List<Field> headers = comparer.headers(text("ID:1", "a").priority(4).group("g1").build(),
                text("ID:2", "a").priority(9).group(null).build());

        assertThat(field(headers, "Priority").change()).isEqualTo(Change.DIFFERENT);
        assertThat(field(headers, "Group ID").change()).isEqualTo(Change.ONLY_LEFT);
        assertThat(field(headers, "Delivery count").change()).isEqualTo(Change.SAME);
    }

    // ---------------------------------------------------------------- properties

    @Test
    @DisplayName("properties: same, different, only one side, empty versus absent, and a type change")
    void properties()
    {
        MessageDetail left = text("ID:1", "a").property("same", "x", "String").property("changed", "1", "String")
                .property("leftOnly", "l", "String").property("empty", "", "String").property("typed", "5", "String")
                .build();
        MessageDetail right = text("ID:2", "a").property("same", "x", "String").property("changed", "2", "String")
                .property("rightOnly", "r", "String").property("typed", "5", "Integer").build();

        List<Field> properties = comparer.properties(left, right);

        assertThat(properties).extracting(Field::name).containsExactly("changed", "empty", "leftOnly", "rightOnly",
                "same", "typed");
        assertThat(field(properties, "same").change()).isEqualTo(Change.SAME);
        assertThat(field(properties, "changed").change()).isEqualTo(Change.DIFFERENT);
        assertThat(field(properties, "leftOnly").change()).isEqualTo(Change.ONLY_LEFT);
        assertThat(field(properties, "rightOnly").change()).isEqualTo(Change.ONLY_RIGHT);
        Field empty = field(properties, "empty");
        assertThat(empty.change()).isEqualTo(Change.ONLY_LEFT);
        assertThat(empty.leftShown()).isEqualTo("(empty)");
        assertThat(empty.rightShown()).isEqualTo("(not set)");
        Field typed = field(properties, "typed");
        assertThat(typed.change()).isEqualTo(Change.DIFFERENT);
        assertThat(typed.leftType()).isEqualTo("String");
        assertThat(typed.rightType()).isEqualTo("Integer");
    }

    // ---------------------------------------------------------------- text bodies

    @Test
    @DisplayName("text: identical bodies say so and draw no lines")
    void identicalText()
    {
        Body body = comparer.body(text("ID:1", "hello\nworld").build(), text("ID:2", "hello\nworld").build());

        assertThat(body.mode()).isEqualTo(BodyMode.TEXT);
        assertThat(body.identical()).isTrue();
        assertThat(body.lines()).isEmpty();
        assertThat(body.limitations()).isEmpty();
    }

    @Test
    @DisplayName("text: a changed line is aligned between unchanged ones, with line numbers on each side")
    void changedLine()
    {
        Body body = comparer.body(text("ID:1", "a\nb\nc").build(), text("ID:2", "a\nB\nc\nd").build());

        assertThat(body.identical()).isFalse();
        assertThat(ops(body)).containsExactly("same a", "removed b", "added B", "same c", "added d");
        Line added = body.lines().get(4);
        assertThat(added.leftNumber()).isZero();
        assertThat(added.rightNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("text: long unchanged runs collapse to a count, keeping three lines of context")
    void collapsesContext()
    {
        String common = IntStream.rangeClosed(1, 20).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
        Body body = comparer.body(text("ID:1", common + "\nold").build(), text("ID:2", common + "\nnew").build());

        assertThat(body.lines().get(0).op()).isEqualTo("skip");
        assertThat(body.lines().get(0).skipped()).isEqualTo(17);
        assertThat(ops(body).subList(1, 6)).containsExactly("same line 18", "same line 19", "same line 20",
                "removed old", "added new");
    }

    @Test
    @DisplayName("text: a carriage return is shown, so CRLF and LF bodies do not look the same")
    void carriageReturnVisible()
    {
        Body body = comparer.body(text("ID:1", "a\r\nb").build(), text("ID:2", "a\nb").build());

        assertThat(body.identical()).isFalse();
        assertThat(body.lines().get(0).textShown()).isEqualTo("a\\r");
    }

    @Test
    @DisplayName("text: a body cut at the read limit is labelled, and a match is only of what was compared")
    void truncatedBody()
    {
        Body body = comparer.body(text("ID:1", "same start").truncated().build(), text("ID:2", "same start").build());

        assertThat(body.identical()).isTrue();
        assertThat(body.explanation()).isEqualTo("The compared text is identical.");
        assertThat(body.limitations()).singleElement().asString().contains("the left body")
                .contains("does not mean matching bodies");
    }

    @Test
    @DisplayName("text: bodies past the compare limit are cut to it and labelled")
    void cutAtCompareLimit()
    {
        MessageComparer small = new MessageComparer(5, 1_000_000, 2_000, 5_000);
        Body body = small.body(text("ID:1", "abcdefXYZ").build(), text("ID:2", "abcdefQRS").build());

        assertThat(body.identical()).isTrue();
        assertThat(body.limitations()).singleElement().asString().contains("first 5 characters of each body");
    }

    @Test
    @DisplayName("text: a changed region too large to align is shown as replaced, and says so")
    void alignmentAbandoned()
    {
        MessageComparer small = new MessageComparer(10_000, 4, 2_000, 5_000);
        Body body = small.body(text("ID:1", "a\n1\n2\n3\nz").build(), text("ID:2", "a\n4\n2\n5\nz").build());

        assertThat(ops(body)).containsExactly("same a", "removed 1", "removed 2", "removed 3", "added 4", "added 2",
                "added 5", "same z");
        assertThat(body.limitations()).singleElement().asString().contains("too large to align");
    }

    @Test
    @DisplayName("text: at most the configured number of diff lines are drawn")
    void boundedLines()
    {
        MessageComparer small = new MessageComparer(10_000, 1_000_000, 3, 5_000);
        Body body = small.body(text("ID:1", "1\n2\n3\n4\n5").build(), text("ID:2", "a\nb\nc\nd\ne").build());

        assertThat(body.lines()).hasSize(3);
        assertThat(body.limitations()).singleElement().asString().contains("only the first 3");
    }

    // ---------------------------------------------------------------- JSON bodies

    @Test
    @DisplayName("JSON: values compared by path; missing, null and empty string are three different things")
    void jsonPaths()
    {
        Body body = comparer.body(
                text("ID:1", "{\"id\":1,\"a\":null,\"b\":\"\",\"c\":\"x\",\"items\":[1,2],\"gone\":true}").build(),
                text("ID:2", "{\"id\":1,\"a\":\"\",\"b\":null,\"items\":[1,3,4],\"new\":{}}").build());

        assertThat(body.mode()).isEqualTo(BodyMode.JSON);
        assertThat(body.identical()).isFalse();
        Map<String, Field> byPath = body.paths().stream()
                .collect(Collectors.toMap(Field::name, f -> f, (x, y) -> x, LinkedHashMap::new));
        assertThat(byPath.keySet()).containsExactly("$.a", "$.b", "$.c", "$.gone", "$.items[1]", "$.items[2]", "$.new");
        assertThat(byPath.get("$.a").leftType()).isNull();
        assertThat(byPath.get("$.a").left()).isEqualTo("null");
        assertThat(byPath.get("$.a").rightType()).isEqualTo("string");
        assertThat(byPath.get("$.a").change()).isEqualTo(Change.DIFFERENT);
        assertThat(byPath.get("$.c").change()).isEqualTo(Change.ONLY_LEFT);
        assertThat(byPath.get("$.items[2]").change()).isEqualTo(Change.ONLY_RIGHT);
        assertThat(byPath.get("$.new").rightType()).isEqualTo("empty object");
        assertThat(byPath.get("$.c").left()).isEqualTo("\"x\"");
        assertThat(body.unchangedPaths()).isEqualTo(2);
    }

    @Test
    @DisplayName("JSON: the same values in another layout and key order are the same, and say why the text differs")
    void jsonLayoutOnly()
    {
        Body body = comparer.body(text("ID:1", "{\"a\":1,\"b\":[true]}").build(),
                text("ID:2", "{\n  \"b\": [ true ],\n  \"a\": 1\n}").build());

        assertThat(body.mode()).isEqualTo(BodyMode.JSON);
        assertThat(body.identical()).isTrue();
        assertThat(body.explanation()).contains("only in layout or key order");
    }

    @Test
    @DisplayName("JSON: a string \"5\" and a number 5 differ, and decimals are not rounded to doubles")
    void jsonTypesAndPrecision()
    {
        Body body = comparer.body(text("ID:1", "{\"n\":\"5\",\"d\":0.10000000000000000001}").build(),
                text("ID:2", "{\"n\":5,\"d\":0.1}").build());

        assertThat(body.paths()).extracting(Field::name).containsExactly("$.d", "$.n");
    }

    @Test
    @DisplayName("JSON: keys needing quotes are written as bracketed paths")
    void jsonQuotedKeys()
    {
        Body body = comparer.body(text("ID:1", "{\"my-key\":1,\"a\\\"b\":1}").build(),
                text("ID:2", "{\"my-key\":2,\"a\\\"b\":2}").build());

        assertThat(body.paths()).extracting(Field::name).containsExactly("$[\"a\\\"b\"]", "$[\"my-key\"]");
    }

    @Test
    @DisplayName("JSON: only whole bodies are parsed; a truncated or invalid one is compared as text")
    void jsonFallsBackToText()
    {
        assertThat(
                comparer.body(text("ID:1", "{\"a\":1}").truncated().build(), text("ID:2", "{\"a\":1}").build()).mode())
                .isEqualTo(BodyMode.TEXT);
        assertThat(comparer.body(text("ID:1", "{\"a\":1").build(), text("ID:2", "{\"a\":1}").build()).mode())
                .isEqualTo(BodyMode.TEXT);
        assertThat(comparer.body(text("ID:1", "{\"a\":1}").build(), text("ID:2", "plain").build()).mode())
                .isEqualTo(BodyMode.TEXT);
        assertThat(comparer.body(text("ID:1", "42").build(), text("ID:2", "42").build()).mode())
                .isEqualTo(BodyMode.TEXT);
    }

    @Test
    @DisplayName("JSON: more values than the limit falls back to text, and says so")
    void jsonTooLarge()
    {
        MessageComparer small = new MessageComparer(10_000, 1_000_000, 2_000, 2);
        Body body = small.body(text("ID:1", "[1,2,3]").build(), text("ID:2", "[1,2,4]").build());

        assertThat(body.mode()).isEqualTo(BodyMode.TEXT);
        assertThat(body.limitations()).singleElement().asString().contains("more than 2 JSON values");
    }

    // ---------------------------------------------------------------- unsupported

    @Test
    @DisplayName("a bytes or map body is not compared, and the page is told which types they were")
    void unsupportedBodies()
    {
        Body body = comparer.body(text("ID:1", "a").type("Bytes").build(), text("ID:2", "a").build());

        assertThat(body.mode()).isEqualTo(BodyMode.NOT_COMPARED);
        assertThat(body.identical()).isFalse();
        assertThat(body.explanation()).contains("a bytes and a text message");
    }

    // ---------------------------------------------------------------- safe rendering

    @Test
    @DisplayName("control, bidirectional and zero-width characters are shown as escapes; ordinary text is untouched")
    void visible()
    {
        assertThat(MessageComparer.visible("plain <b>&amp; ünïcode\ttab")).isEqualTo("plain <b>&amp; ünïcode\ttab");
        assertThat(MessageComparer.visible("a‮b")).isEqualTo("a\\u202Eb");
        assertThat(MessageComparer.visible("zero​width﻿")).isEqualTo("zero\\u200Bwidth\\uFEFF");
        assertThat(MessageComparer.visible("bell\u0007")).isEqualTo("bell\\u0007");
        assertThat(MessageComparer.visible("x\ny")).isEqualTo("x\\ny");
        assertThat(MessageComparer.shown(null)).isEqualTo("(not set)");
        assertThat(MessageComparer.shown("")).isEqualTo("(empty)");
    }

    // ---------------------------------------------------------------- fixtures

    private static List<String> ops(Body body)
    {
        return body.lines().stream().map(line -> line.op() + " " + line.text()).toList();
    }

    private static Field field(List<Field> fields, String name)
    {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst().orElseThrow();
    }

    private static Builder text(String id, String body)
    {
        return new Builder(id, body);
    }

    private static final class Builder
    {

        private final String id;
        private final String body;
        private String type = "Text";
        private String correlation;
        private String group;
        private int priority = 4;
        private boolean truncated;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final Map<String, String> types = new LinkedHashMap<>();

        Builder(String id, String body)
        {
            this.id = id;
            this.body = body;
        }

        Builder type(String value)
        {
            type = value;
            return this;
        }

        Builder correlation(String value)
        {
            correlation = value;
            return this;
        }

        Builder group(String value)
        {
            group = value;
            return this;
        }

        Builder priority(int value)
        {
            priority = value;
            return this;
        }

        Builder truncated()
        {
            truncated = true;
            return this;
        }

        Builder property(String name, String value, String javaType)
        {
            properties.put(name, value);
            types.put(name, javaType);
            return this;
        }

        MessageDetail build()
        {
            return new MessageDetail("orders", id, correlation, type, "queue://orders", "2026-09-30 10:00:00", "never",
                    priority, true, false, 0, group, false, body, truncated, properties, types);
        }
    }
}
