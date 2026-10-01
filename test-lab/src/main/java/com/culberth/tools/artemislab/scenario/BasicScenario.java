package com.culberth.tools.artemislab.scenario;

import jakarta.jms.TextMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code BASIC} (C01–C04, M01): queues whose counts are known exactly, for the overview, paging and navigation.
 *
 * <ul>
 * <li>{@code empty} — nothing, so the empty state can be checked.</li>
 * <li>{@code one} — one persistent message.</li>
 * <li>{@code paged} — 251 persistent messages, {@code seq} 1..251: six pages at size 50, one row on the last.</li>
 * <li>{@code counters} — 12 sent, 3 consumed and acknowledged, 2 of the rest scheduled an hour out: waiting 7,
 * scheduled 2, messageCount 9, added 12, acknowledged 3. Delivering, expired and killed need held consumers and come
 * with {@code DELIVERY}.</li>
 * <li>{@code odd name & ü 日本} — a name with spaces, an ampersand and non-ASCII, for link encoding and escaping.</li>
 * </ul>
 */
@Component
public class BasicScenario implements Recipe
{

    public static final String ID = "BASIC";
    static final int PAGED = 251;
    static final int BODY = 60;

    @Override
    public String id()
    {
        return ID;
    }

    /** The queue suffixes, in creation order. */
    static List<String> suffixes()
    {
        return List.of("empty", "one", "paged", "counters", "odd name & ü 日本");
    }

    @Override
    public String run(Fixture fixture, Map<String, Integer> params) throws Exception
    {
        List<String> queues = suffixes().stream().map(fixture::name).toList();
        fixture.requireNew(queues);
        fixture.reserve((long) (1 + PAGED + 12 + 1) * BODY);
        for (String queue : queues)
        {
            fixture.createQueue(queue);
        }
        String one = fixture.name("one");
        String paged = fixture.name("paged");
        String counters = fixture.name("counters");
        String odd = fixture.name("odd name & ü 日本");

        try (Sender sender = fixture.sender())
        {
            sender.send(one, 1, text(sender, "basic one"), "text", BODY, "the only message", Sender.Options.PERSISTENT);
            for (int i = 1; i <= PAGED; i++)
            {
                TextMessage message = text(sender, "basic paged " + i);
                message.setIntProperty("seq", i);
                sender.send(paged, i, message, "text", BODY, i == PAGED ? "alone on the last page at size 50" : "",
                        Sender.Options.PERSISTENT);
            }
            for (int i = 1; i <= 10; i++)
            {
                sender.send(counters, i, text(sender, "basic counters " + i), "text", BODY,
                        i <= 3 ? "consumed and acknowledged" : "waiting", Sender.Options.PERSISTENT);
            }
            sender.send(odd, 1, text(sender, "basic odd name"), "text", BODY, "", Sender.Options.PERSISTENT);
        }
        int consumed = fixture.consume(counters, 3);
        if (consumed != 3)
        {
            throw new IllegalStateException("Consumed " + consumed + " of 3 from " + counters);
        }
        try (Sender sender = fixture.sender())
        {
            long delay = Duration.ofHours(1).toMillis();
            for (int i = 11; i <= 12; i++)
            {
                sender.send(counters, i, text(sender, "basic counters scheduled " + i), "text", BODY,
                        "scheduled one hour out", Sender.Options.PERSISTENT.withDeliveryDelay(delay));
            }
        }

        List<String> asserted = new ArrayList<>();
        asserted.add(fixture.await(fixture.name("empty"), Map.of("messageCount", 0L)));
        asserted.add(fixture.await(one, Map.of("messageCount", 1L)));
        asserted.add(fixture.await(paged, Map.of("messageCount", (long) PAGED)));
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("messageCount", 9L);
        expected.put("scheduledCount", 2L);
        expected.put("messagesAdded", 12L);
        expected.put("messagesAcknowledged", 3L);
        asserted.add(fixture.await(counters, expected));
        asserted.add(fixture.await(odd, Map.of("messageCount", 1L)));
        return "Asserted " + asserted.size() + " queues; counters: " + asserted.get(3) + ".";
    }

    /** A text body of exactly {@link #BODY} characters. */
    private static TextMessage text(Sender sender, String head) throws jakarta.jms.JMSException
    {
        return sender.session().createTextMessage(pad(head, BODY));
    }

    static String pad(String head, int length)
    {
        return head.length() >= length ? head.substring(0, length) : head + ".".repeat(length - head.length());
    }
}
