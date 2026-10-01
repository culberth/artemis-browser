package com.culberth.tools.artemislab.scenario;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.ObjectMessage;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code BODIES} (M03, M04, M06, M08, M09): one queue, {@code bodies}, holding one message of every body kind and the
 * awkward content an export must survive. In order, by {@code labSeq}:
 *
 * <ol>
 * <li>plain text</li>
 * <li>text with Unicode, both quote kinds, a newline and literal HTML that must be shown, not rendered</li>
 * <li>empty text, 4. null text</li>
 * <li>bytes 0..63, 6. empty bytes</li>
 * <li>map, 8. stream, 9. object (a {@code String})</li>
 * <li>text carrying one property of every type, with a {@code labCase} naming each</li>
 * <li>1,000-character text</li>
 * <li>250,000-character text and 13. 250,000 bytes — over the broker's default 100KB large-message threshold</li>
 * <li>14–17. bodies and a {@code note} property starting with {@code =}, {@code +}, {@code -} and {@code @}, with
 * commas, quotes and newlines</li>
 * </ol>
 *
 * Every message carries {@code labCase}, so Browser's views and exports can be matched to this list.
 */
@Component
public class BodiesScenario implements Recipe
{

    public static final String ID = "BODIES";
    static final int COUNT = 17;
    static final int LARGE = 250_000;

    static final String HTML_TEXT = "Ünïcödé ✓ 日本語 — \"double\" 'single'\nsecond line <b>not bold</b> "
            + "<script>alert('not run')</script> &amp; &lt;";
    static final List<String> FORMULAS = List.of("=1+1", "+1+1", "-2+3", "@SUM(1,2)");

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public String run(Fixture fixture, Map<String, Integer> params) throws Exception
    {
        String queue = fixture.name("bodies");
        fixture.requireNew(List.of(queue));
        fixture.reserve(2L * LARGE + 2_000 + 1_000);
        fixture.createQueue(queue);
        try (Sender sender = fixture.sender())
        {
            var session = sender.session();
            int seq = 0;

            send(sender, queue, ++seq, session.createTextMessage("Hello from the lab"), "text", 18, "plain-text");
            send(sender, queue, ++seq, session.createTextMessage(HTML_TEXT), "text", HTML_TEXT.length(),
                    "unicode-quotes-newline-html");
            send(sender, queue, ++seq, session.createTextMessage(""), "text", 0, "empty-text");
            send(sender, queue, ++seq, session.createTextMessage(), "text", 0, "null-text");

            BytesMessage bytes = session.createBytesMessage();
            byte[] ramp = new byte[64];
            for (int i = 0; i < ramp.length; i++)
            {
                ramp[i] = (byte) i;
            }
            bytes.writeBytes(ramp);
            send(sender, queue, ++seq, bytes, "bytes", 64, "bytes-0-to-63");
            send(sender, queue, ++seq, session.createBytesMessage(), "bytes", 0, "empty-bytes");

            MapMessage map = session.createMapMessage();
            map.setString("name", "lab map");
            map.setInt("count", 42);
            map.setDouble("ratio", 0.25);
            map.setBoolean("flag", true);
            send(sender, queue, ++seq, map, "map", 0, "map-four-entries");

            StreamMessage stream = session.createStreamMessage();
            stream.writeString("lab stream");
            stream.writeInt(7);
            stream.writeBoolean(false);
            send(sender, queue, ++seq, stream, "stream", 0, "stream-three-values");

            ObjectMessage object = session.createObjectMessage("a harmless serialized java.lang.String");
            send(sender, queue, ++seq, object, "object", 0, "object-string");

            TextMessage typed = session.createTextMessage("typed properties");
            typed.setStringProperty("pString", "text ü");
            typed.setIntProperty("pInt", 2147483647);
            typed.setLongProperty("pLong", 9_000_000_000L);
            typed.setDoubleProperty("pDouble", 3.5);
            typed.setFloatProperty("pFloat", 1.25f);
            typed.setBooleanProperty("pBoolean", true);
            typed.setShortProperty("pShort", (short) 12);
            typed.setByteProperty("pByte", (byte) -3);
            send(sender, queue, ++seq, typed, "text", 16, "typed-properties");

            String thousand = repeat("long text body. ", 1_000);
            send(sender, queue, ++seq, session.createTextMessage(thousand), "text", 1_000, "text-1000");

            String large = repeat("large text body. ", LARGE);
            send(sender, queue, ++seq, session.createTextMessage(large), "text", LARGE, "large-text-250000");
            BytesMessage largeBytes = session.createBytesMessage();
            largeBytes.writeBytes(repeat("large bytes body. ", LARGE).getBytes(StandardCharsets.US_ASCII));
            send(sender, queue, ++seq, largeBytes, "bytes", LARGE, "large-bytes-250000");

            for (String formula : FORMULAS)
            {
                TextMessage message = session.createTextMessage(formula);
                message.setStringProperty("note", formula + ", \"quoted\"\nnext line");
                send(sender, queue, ++seq, message, "text", formula.length(), "formula-" + formula.charAt(0));
            }
        }
        return fixture.await(queue, Map.of("messageCount", (long) COUNT)) + ". Each message's labCase names it.";
    }

    private static void send(Sender sender, String queue, int seq, Message message, String kind, int bodyBytes,
            String labCase) throws JMSException
    {
        message.setStringProperty("labCase", labCase);
        sender.send(queue, seq, message, kind, bodyBytes, labCase, Sender.Options.PERSISTENT);
    }

    /** {@code unit} repeated and cut to exactly {@code length} characters. */
    static String repeat(String unit, int length)
    {
        return unit.repeat(length / unit.length() + 1).substring(0, length);
    }
}
