package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the XA transaction branches this broker holds prepared, and the ones resolved by hand. Offers nothing that
 * resolves one: {@code commitPreparedTransaction} and {@code rollbackPreparedTransaction} are not on
 * {@link ManagementChannel#READ_OPERATIONS}, and only a transaction manager should call them.
 *
 * <p>
 * The reads, as recorded on 2.55.0 and 2.57.0 in {@code .claude/memory.md}:
 * <ul>
 * <li>{@code listPreparedTransactions} — one line per branch, creation time and Xid. Cheap, so it is read first, for
 * the count.</li>
 * <li>{@code listPreparedTransactionDetailsAsJSON} — per branch its Xid parts and every message it sends or receives,
 * headers and properties, no bodies; {@code ""}, not {@code []}, when there are none. It has no paging and nothing
 * bounds one branch's message list, so it is read only up to {@link #DETAIL_LIMIT} branches, and each branch keeps at
 * most {@link #MESSAGE_LIMIT} messages.</li>
 * <li>{@code listHeuristicCommittedTransactions}, {@code listHeuristicRolledBackTransactions} — base64 Xids.</li>
 * </ul>
 *
 * <p>
 * The creation time comes as the broker's JVM formats a date — {@code 9/30/26, 7:41:25 AM} in its locale and time zone,
 * with no zone written. To turn it into an age the zone is worked out from a connection the broker lists both ways:
 * {@code listConnections} writes its creation time as a {@code Date.toString()} in the same zone, and
 * {@code listConnectionsAsJSON} as epoch millis. Their difference, to the nearest quarter hour, is the broker's offset.
 * Two more calls, made only when something is prepared; if either fails, or the locale is not the one this reads, the
 * time is shown as the broker wrote it and no age is claimed.
 */
@Service
public class TransactionService
{

    /** Most prepared branches whose details one page reads. Past this only the summary lines are. */
    static final int DETAIL_LIMIT = 100;

    /** Most messages kept per branch. The rest are counted. */
    static final int MESSAGE_LIMIT = 50;

    /** A branch prepared more recently than this may be a transaction manager between prepare and commit. */
    static final long RECENT_MILLIS = 60_000;

    private static final String NO_FILTER = "{\"field\":\"\",\"operation\":\"\",\"value\":\"\"}";

    /** The message's headers. The reply puts the properties at the same level, so what is not a header is one. */
    private static final Set<String> OWN_FIELDS = Set.of("address", "messageID", "type", "priority", "userID",
            "durable", "expiration", "timestamp");
    private static final Set<String> INTERNAL_PROPERTIES = Set.of("__AMQ_CID", "_AMQ_ROUTING_TYPE");
    private static final String OPENWIRE_HEADER_PREFIX = "__HDR_";

    /** {@code DateFormat.getDateTimeInstance(SHORT, MEDIUM)} in {@code en-US}, the format recorded. */
    private static final DateTimeFormatter CREATION = DateTimeFormatter.ofPattern("M/d/yy, h:mm:ss a", Locale.US);

    /** {@code Date.toString()} with its zone name taken out: the name is ambiguous, the difference is not. */
    private static final DateTimeFormatter DATE_TO_STRING = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy",
            Locale.ENGLISH);

    private final BrokerSession brokerSession;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int detailLimit;

    public TransactionService(BrokerSession brokerSession,
            @Value("${artemis.transactions.detail-limit:" + DETAIL_LIMIT + "}") int detailLimit)
    {
        this.brokerSession = brokerSession;
        this.detailLimit = Math.max(0, detailLimit);
    }

    public int detailLimit()
    {
        return detailLimit;
    }

    /** Everything, each part on its own: a broker that refuses the heuristic lists still shows what is prepared. */
    public Transactions collect()
    {
        ManagementChannel management = brokerSession.requireManagement();
        Instant started = Instant.now();
        Reading<List<String>> summaries = Reading.attempt(
                () -> ConnectivityService.strings(management.invoke(ResourceNames.BROKER, "listPreparedTransactions")));
        Reading<List<PreparedTransaction>> prepared;
        int total = 0;
        String skipped = "";
        String clockNote = "";
        if (!summaries.available())
        {
            prepared = summaries.absent();
        }
        else if (summaries.value().isEmpty())
        {
            prepared = Reading.of(List.of());
        }
        else
        {
            total = summaries.value().size();
            BrokerClock clock = clock(management);
            clockNote = clock.note();
            if (total > detailLimit)
            {
                skipped = total + " branches are prepared, more than the " + detailLimit
                        + " whose messages this page reads (artemis.transactions.detail-limit). The broker returns"
                        + " every branch's messages in one reply with no paging, so only their summary lines were read.";
                prepared = Reading.of(summaries.value().stream().map(line -> fromSummary(line, clock)).toList());
            }
            else
            {
                prepared = Reading.attempt(() -> details(
                        management.invoke(ResourceNames.BROKER, "listPreparedTransactionDetailsAsJSON"), clock));
            }
        }
        Reading<List<String>> committed = Reading.attempt(() -> ConnectivityService
                .strings(management.invoke(ResourceNames.BROKER, "listHeuristicCommittedTransactions")));
        Reading<List<String>> rolledBack = Reading.attempt(() -> ConnectivityService
                .strings(management.invoke(ResourceNames.BROKER, "listHeuristicRolledBackTransactions")));
        return new Transactions(prepared, total, skipped, committed, rolledBack, clockNote, started);
    }

    // ------------------------------------------------------------------ parsing

    /** The broker's UTC offset, or why it could not be worked out. */
    record BrokerClock(ZoneOffset offset, String note)
    {
    }

    BrokerClock clock(ManagementChannel management)
    {
        try
        {
            JsonNode page = objectMapper.readTree(
                    String.valueOf(management.invoke(ResourceNames.BROKER, "listConnections", NO_FILTER, 1, 1)));
            JsonNode first = page.path("data").path(0);
            String id = text(first, "connectionID");
            String local = text(first, "creationTime");
            if (id.isEmpty() || local.isEmpty())
            {
                return new BrokerClock(null,
                        "Ages are not shown: the broker listed no connection to compare its" + " clock with.");
            }
            for (JsonNode node : array(management.invoke(ResourceNames.BROKER, "listConnectionsAsJSON")))
            {
                if (id.equals(text(node, "connectionID")) && node.path("creationTime").canConvertToLong())
                {
                    return offset(local, node.path("creationTime").asLong());
                }
            }
            return new BrokerClock(null, "Ages are not shown: connection " + id + " was not in the second listing"
                    + " needed to work out the broker's time zone.");
        }
        catch (ConnectionLostException e)
        {
            throw e;
        }
        catch (RuntimeException e)
        {
            return new BrokerClock(null,
                    "Ages are not shown: the broker's time zone could not be worked out (" + e.getMessage() + ").");
        }
    }

    /**
     * The offset between a {@code Date.toString()} and the epoch millis of the same moment. Both are to the second at
     * best, and real offsets are whole quarter hours, so the difference is rounded to one.
     */
    static BrokerClock offset(String dateToString, long epochMillis)
    {
        String[] parts = dateToString.trim().split("\\s+");
        if (parts.length != 6)
        {
            return new BrokerClock(null,
                    "Ages are not shown: '" + dateToString + "' is not the date format this tool reads.");
        }
        try
        {
            LocalDateTime local = LocalDateTime
                    .parse(String.join(" ", parts[0], parts[1], parts[2], parts[3], parts[5]), DATE_TO_STRING);
            long seconds = local.toEpochSecond(ZoneOffset.UTC) - Math.floorDiv(epochMillis, 1000);
            long quarterHours = Math.round(seconds / 900.0);
            if (Math.abs(quarterHours) > 18 * 4)
            {
                return new BrokerClock(null, "Ages are not shown: the broker's clock and zone did not agree.");
            }
            ZoneOffset offset = ZoneOffset.ofTotalSeconds((int) (quarterHours * 900));
            return new BrokerClock(offset,
                    "Creation times are the broker's own, in its time zone (UTC"
                            + (offset.getTotalSeconds() == 0 ? "" : offset.getId()) + ", worked out from its connection"
                            + " listings); ages are from them.");
        }
        catch (DateTimeParseException e)
        {
            return new BrokerClock(null,
                    "Ages are not shown: '" + dateToString + "' is not the date format this tool reads.");
        }
    }

    /**
     * A creation time as an instant, or the reason it is not one. The broker writes a narrow no-break space before
     * AM/PM on current JDKs and an ordinary space on older ones; both are read.
     */
    static Instant created(String text, BrokerClock clock)
    {
        if (clock.offset() == null || text == null || text.isBlank())
        {
            return null;
        }
        try
        {
            String normal = text.replace(' ', ' ').replace(' ', ' ').trim();
            return LocalDateTime.parse(normal, CREATION).toInstant(clock.offset());
        }
        catch (DateTimeParseException e)
        {
            return null;
        }
    }

    private static String createdNote(String text, Instant created, BrokerClock clock)
    {
        if (created != null)
        {
            return "";
        }
        if (clock.offset() == null)
        {
            return clock.note();
        }
        return "'" + text + "' is not a date format this tool reads — the broker writes it in its own locale — so no"
                + " age is shown.";
    }

    /** {@code 9/30/26, 7:41:25 AM base64: YnJh…AAA= XidImpl (…)}: the time and the Xid, nothing about messages. */
    PreparedTransaction fromSummary(String line, BrokerClock clock)
    {
        int at = line.indexOf(" base64: ");
        String createdText = at < 0 ? "" : line.substring(0, at).trim();
        String rest = at < 0 ? line.trim() : line.substring(at + " base64: ".length()).trim();
        int space = rest.indexOf(' ');
        String xid = space < 0 ? rest : rest.substring(0, space);
        Instant created = created(createdText, clock);
        return new PreparedTransaction(xid, -1, "", "", createdText, created,
                createdText.isEmpty() ? "The broker's summary line gave no creation time."
                        : createdNote(createdText, created, clock),
                List.of(), 0, false);
    }

    List<PreparedTransaction> details(Object result, BrokerClock clock)
    {
        List<PreparedTransaction> transactions = new ArrayList<>();
        for (JsonNode node : array(result))
        {
            String createdText = text(node, "creation_time");
            Instant created = created(createdText, clock);
            List<TransactionMessage> messages = new ArrayList<>();
            int total = 0;
            for (JsonNode message : node.path("tx_related_messages"))
            {
                total++;
                if (messages.size() < MESSAGE_LIMIT)
                {
                    messages.add(message(message));
                }
            }
            transactions.add(new PreparedTransaction(text(node, "xid_as_base64"), node.path("xid_format_id").asInt(-1),
                    text(node, "xid_global_txid"), text(node, "xid_branch_qual"), createdText, created,
                    createdNote(createdText, created, clock), messages, total, true));
        }
        return transactions;
    }

    private static TransactionMessage message(JsonNode node)
    {
        String raw = text(node, "message_operation_type");
        JsonNode props = node.path("message_properties");
        Map<String, String> properties = new LinkedHashMap<>();
        props.propertyStream().forEach(field ->
        {
            String name = field.getKey();
            if (!OWN_FIELDS.contains(name) && !INTERNAL_PROPERTIES.contains(name)
                    && !name.startsWith(OPENWIRE_HEADER_PREFIX))
            {
                properties.put(name, field.getValue().asString());
            }
        });
        return new TransactionMessage(TransactionMessage.operation(raw), raw, text(node, "message_type"),
                text(props, "address"), text(props, "messageID"), text(props, "userID"),
                props.path("timestamp").asLong(0L), props.path("durable").asBoolean(false),
                props.path("priority").asInt(4), properties);
    }

    /** A JSON array; {@code ""}, which the broker returns when nothing is prepared, is an empty one. */
    private List<JsonNode> array(Object result)
    {
        if (result == null || result.toString().isBlank())
        {
            return List.of();
        }
        try
        {
            JsonNode root = objectMapper.readTree(result.toString());
            if (!root.isArray())
            {
                throw new BrokerException("expected a JSON array, got: " + abbreviate(result.toString()));
            }
            List<JsonNode> nodes = new ArrayList<>();
            root.forEach(nodes::add);
            return nodes;
        }
        catch (BrokerException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new BrokerException("Could not read the broker's response: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text)
    {
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    // ------------------------------------------------------------------ findings

    /**
     * Diagnose's transaction findings: each prepared branch, with the messages it holds out of reach, and branches an
     * operator resolved by hand. What could not be read goes to {@code unchecked}.
     */
    public static void findings(Transactions transactions, Instant now, List<Finding> findings, List<String> unchecked)
    {
        if (!transactions.prepared().available())
        {
            unchecked.add("Prepared XA transactions — whether any hold messages where no consumer or browse can reach"
                    + " them: " + transactions.prepared().explained());
        }
        else
        {
            transactions.prepared().value().forEach(tx -> findings.add(prepared(transactions, tx, now)));
        }

        List<String> committed = transactions.heuristicCommitted().orElse(List.of());
        List<String> rolledBack = transactions.heuristicRolledBack().orElse(List.of());
        if (!transactions.heuristicCommitted().available() || !transactions.heuristicRolledBack().available())
        {
            Reading<?> failed = transactions.heuristicCommitted().available() ? transactions.heuristicRolledBack()
                    : transactions.heuristicCommitted();
            unchecked.add("XA transactions resolved by hand on this broker: " + failed.explained());
        }
        if (!committed.isEmpty() || !rolledBack.isEmpty())
        {
            findings.add(new Finding(Finding.WATCH,
                    (committed.size() + rolledBack.size()) + " XA transaction branch(es) were resolved by hand",
                    "The broker records " + describe(committed, "committed")
                            + (committed.isEmpty() || rolledBack.isEmpty() ? "" : " and ")
                            + describe(rolledBack, "rolled back")
                            + " through its management rather than by their transaction manager. If other resources"
                            + " in the same transaction went the other way, the outcome is inconsistent; the"
                            + " transaction manager learns of it on recovery. The broker keeps these until it is told"
                            + " to forget them.",
                    null, null));
        }
    }

    private static String describe(List<String> xids, String how)
    {
        if (xids.isEmpty())
        {
            return "";
        }
        return xids.size() + " " + how + " (" + String.join(", ", xids.stream().limit(5).toList())
                + (xids.size() > 5 ? ", …" : "") + ")";
    }

    private static Finding prepared(Transactions transactions, PreparedTransaction tx, Instant now)
    {
        Long age = tx.ageMillis(now);
        String since = tx.createdText().isEmpty() ? ""
                : " since " + tx.createdText() + (age == null ? " (the broker's time; no age could be worked out)"
                        : " (" + AddressDetail.ageText(age) + " ago)");
        StringBuilder detail = new StringBuilder("The broker holds this XA branch prepared" + since + ": its"
                + " transaction manager has not said commit or roll back. ");
        Map<String, Long> held = tx.heldByAddress();
        Map<String, Long> pending = tx.pendingByAddress();
        if (!tx.detailRead())
        {
            detail.append("Its messages were not read: ").append(transactions.detailSkipped()).append(' ');
        }
        if (!held.isEmpty())
        {
            detail.append("It holds ").append(tx.receives()).append(" received message(s) — ")
                    .append(list(held, "from"))
                    .append(" — on their queues as delivering, with no consumer holding them. Browse and the"
                            + " in-flight list do not show them, and no consumer gets them until it is resolved. ");
        }
        if (!pending.isEmpty())
        {
            detail.append("It sent ").append(tx.sends()).append(" message(s) — ").append(list(pending, "to"))
                    .append(" — which reach those addresses only if it commits. ");
        }
        if (tx.truncated())
        {
            detail.append("(").append(tx.messageTotal()).append(" message(s) in all; the first ")
                    .append(tx.messages().size()).append(" were read.) ");
        }
        detail.append("Only its transaction manager should resolve it; this tool cannot.");
        boolean recent = age != null && age < RECENT_MILLIS;
        String severity = held.isEmpty() || recent ? Finding.WATCH : Finding.STUCK;
        String address = tx.addresses().isEmpty() ? null : tx.addresses().get(0);
        Finding finding = new Finding(severity, "Prepared XA transaction " + tx.title() + " is unresolved",
                detail.toString().trim(), null, address);
        return recent
                ? finding.explainedBy("Prepared " + AddressDetail.ageText(age) + " ago. A transaction manager"
                        + " between its prepare and its commit looks exactly like this, and a moment later it is gone.")
                : finding;
    }

    private static String list(Map<String, Long> byAddress, String preposition)
    {
        return byAddress.entrySet().stream()
                .map(entry -> entry.getValue() + " " + preposition + " '" + entry.getKey() + "'")
                .collect(Collectors.joining(", "));
    }
}
