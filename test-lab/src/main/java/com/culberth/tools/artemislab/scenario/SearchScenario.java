package com.culberth.tools.artemislab.scenario;

import jakarta.jms.DeliveryMode;
import jakarta.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code SEARCH} (M02, M05, M06, M07): matches placed where naive counting misses them.
 *
 * <ul>
 * <li>{@code search-a} — 300 messages. {@code marker = 'late'} on 251–260 only, past the 200 a filtered
 * {@code countMessages} looks at. Artemis keeps a queue in priority order (highest first, then arrival), so the late
 * messages get priority 0 and every other message {@link #priority(int)} 1–9: that puts them at queue positions
 * 291–300, where browse and paging show them. Odd {@code seq} persistent, even non-persistent; {@code region} eu/us
 * alternating; {@code amount = seq * 3}; sent a millisecond apart so timestamps differ.</li>
 * <li>{@code search-b} — 20 messages, {@code marker = 'late'} on 5–8: a second queue for cross-queue search.</li>
 * <li>{@code search-many} — 60 messages, all {@code marker = 'many'}: past Browser's 50-per-queue search cap.</li>
 * <li>{@code export-bounds} — 10 bodies of 400 characters, {@code marker = 'deep'} on 7–10: past a body-scan limit of 3
 * in M07's reduced-limit Browser profile.</li>
 * </ul>
 *
 * The assertions count matches with a JMS {@code QueueBrowser}, which reads the whole queue. Message ids of every send
 * are in the run's send manifest, for exact-id lookups.
 */
@Component
public class SearchScenario implements Recipe
{

    public static final String ID = "SEARCH";
    static final int BODY = 80;
    static final int LONG_BODY = 400;

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public String run(Fixture fixture, Map<String, Integer> params) throws Exception
    {
        String a = fixture.name("search-a");
        String b = fixture.name("search-b");
        String many = fixture.name("search-many");
        String bounds = fixture.name("export-bounds");
        fixture.requireNew(List.of(a, b, many, bounds));
        fixture.reserve((300L + 20 + 60) * BODY + 10L * LONG_BODY);
        for (String queue : List.of(a, b, many, bounds))
        {
            fixture.createQueue(queue);
        }
        try (Sender sender = fixture.sender())
        {
            for (int seq = 1; seq <= 300; seq++)
            {
                TextMessage message = text(sender, "search-a " + seq, BODY);
                message.setStringProperty("region", seq % 2 == 0 ? "us" : "eu");
                message.setIntProperty("amount", seq * 3);
                boolean late = seq >= 251 && seq <= 260;
                if (late)
                {
                    message.setStringProperty("marker", "late");
                }
                Sender.Options options = Sender.Options.PERSISTENT.withPriority(priority(seq))
                        .withDeliveryMode(seq % 2 == 1 ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT);
                sender.send(a, seq, message, "text", BODY, late ? "marker=late" : "", options);
                // Distinct JMSTimestamps, so a timestamp filter has edges to find.
                Thread.sleep(1);
            }
            for (int seq = 1; seq <= 20; seq++)
            {
                TextMessage message = text(sender, "search-b " + seq, BODY);
                message.setStringProperty("region", "eu");
                boolean late = seq >= 5 && seq <= 8;
                if (late)
                {
                    message.setStringProperty("marker", "late");
                }
                sender.send(b, seq, message, "text", BODY, late ? "marker=late" : "", Sender.Options.PERSISTENT);
            }
            for (int seq = 1; seq <= 60; seq++)
            {
                TextMessage message = text(sender, "search-many " + seq, BODY);
                message.setStringProperty("marker", "many");
                sender.send(many, seq, message, "text", BODY, "marker=many", Sender.Options.PERSISTENT);
            }
            for (int seq = 1; seq <= 10; seq++)
            {
                TextMessage message = text(sender, "export-bounds " + seq + " ", LONG_BODY);
                boolean deep = seq >= 7;
                if (deep)
                {
                    message.setStringProperty("marker", "deep");
                }
                sender.send(bounds, seq, message, "text", LONG_BODY, deep ? "marker=deep" : "",
                        Sender.Options.PERSISTENT);
            }
        }

        List<String> asserted = new ArrayList<>();
        asserted.add(fixture.await(a, Map.of("messageCount", 300L)));
        asserted.add(fixture.await(b, Map.of("messageCount", 20L)));
        asserted.add(fixture.await(many, Map.of("messageCount", 60L)));
        asserted.add(fixture.await(bounds, Map.of("messageCount", 10L)));
        asserted.add(fixture.awaitSelected(a, "marker = 'late'", 10));
        asserted.add(fixture.awaitSelected(b, "marker = 'late'", 4));
        asserted.add(fixture.awaitSelected(many, "marker = 'many'", 60));
        asserted.add(fixture.awaitSelected(bounds, "marker = 'deep'", 4));
        asserted.add(fixture.awaitSelected(a, "region = 'eu' AND amount > 600", 50));
        return "Asserted " + asserted.size() + " counts and selections. Core filters to try in Browser: "
                + "marker = 'late'; AMQPriority = 9; AMQDurable = 'NON_DURABLE'; region = 'eu' AND amount > 600.";
    }

    /**
     * 0 for the late markers, otherwise {@code 1 + seq % 9}. Varying priorities on the late messages themselves would
     * move them forward: seq 259 at priority 9 browsed 26th, not 259th.
     */
    public static int priority(int seq)
    {
        return seq >= 251 && seq <= 260 ? 0 : 1 + seq % 9;
    }

    private static TextMessage text(Sender sender, String head, int length) throws jakarta.jms.JMSException
    {
        return sender.session().createTextMessage(BasicScenario.pad(head + " ", length));
    }
}
