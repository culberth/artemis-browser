package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One XA transaction branch the broker holds prepared: its transaction manager has asked it to promise to commit, and
 * has not yet said commit or roll back. Until it does, what the branch sent is on no queue and what it received stays
 * on its queue as delivering — measured on 2.55.0 and 2.57.0, invisible to browse and to the in-flight list.
 *
 * <p>
 * Only prepared branches are visible. A transaction still active (not yet prepared), a JMS transacted session, and the
 * transaction manager's own log are not reported by any management operation, so none of that is here.
 *
 * @param xid          the branch's Xid, base64, as the broker's other transaction operations name it
 * @param formatId     the Xid's format id; -1 when only the summary was read
 * @param globalId     the Xid's global transaction id as text, the way the broker renders it; empty when only the
 *                     summary was read
 * @param branch       the Xid's branch qualifier as text; empty likewise
 * @param createdText  when the broker created it, in the broker's own format and time zone ({@code 9/30/26, 7:41:25
 *                       AM}), exactly as given
 * @param created      the same as an instant, when it could be read — see {@code createdNote} when it could not
 * @param createdNote  why {@code created} is null; empty when it is not
 * @param messages     the messages it will act on, at most {@link TransactionService#MESSAGE_LIMIT}
 * @param messageTotal how many the broker listed; more than {@code messages} holds when some were not kept
 * @param detailRead   false when only the summary line was read, so there are no messages at all
 */
public record PreparedTransaction(String xid, int formatId, String globalId, String branch, String createdText,
        Instant created, String createdNote, List<TransactionMessage> messages, int messageTotal, boolean detailRead)
{

    public long sends()
    {
        return messages.stream().filter(TransactionMessage::send).count();
    }

    public long receives()
    {
        return messages.stream().filter(TransactionMessage::receive).count();
    }

    public boolean truncated()
    {
        return messageTotal > messages.size();
    }

    /** Received messages by the address they were sent to — where they wait, as delivering. Order of first sight. */
    public Map<String, Long> heldByAddress()
    {
        Map<String, Long> held = new LinkedHashMap<>();
        messages.stream().filter(TransactionMessage::receive)
                .forEach(message -> held.merge(message.address(), 1L, Long::sum));
        return held;
    }

    /** Sent messages by address — what reaches those addresses only if it commits. */
    public Map<String, Long> pendingByAddress()
    {
        Map<String, Long> pending = new LinkedHashMap<>();
        messages.stream().filter(TransactionMessage::send)
                .forEach(message -> pending.merge(message.address(), 1L, Long::sum));
        return pending;
    }

    /** Every address it touches, receives first. */
    public List<String> addresses()
    {
        Map<String, Boolean> all = new LinkedHashMap<>();
        heldByAddress().keySet().forEach(address -> all.put(address, true));
        pendingByAddress().keySet().forEach(address -> all.put(address, true));
        return List.copyOf(all.keySet());
    }

    /** How long since it was created, or null when the creation time could not be read. */
    public Long ageMillis(Instant now)
    {
        return created == null ? null : Math.max(0, now.toEpochMilli() - created.toEpochMilli());
    }

    public String ageText(Instant now)
    {
        Long age = ageMillis(now);
        return age == null ? "" : AddressDetail.ageText(age);
    }

    /** The global id and branch where read, else the Xid itself — something a person can match to their TM's log. */
    public String title()
    {
        if (!globalId.isEmpty())
        {
            return branch.isEmpty() ? globalId : globalId + " / " + branch;
        }
        return xid;
    }
}
