package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.List;

/**
 * The XA transaction branches this broker holds prepared, and those an operator has already resolved by hand — all as
 * the broker reports them, and nothing else about transactions, because nothing else is reported.
 *
 * <p>
 * Heuristic lists hold branches that were committed or rolled back through the broker's management rather than by their
 * transaction manager. The broker keeps them so the manager can be told on recovery; each is an Xid only.
 *
 * @param prepared            the prepared branches, with detail when {@code detailSkipped} is empty
 * @param preparedTotal       how many the broker listed; known even when the details were not read
 * @param detailSkipped       why the details were not read — over {@link TransactionService#DETAIL_LIMIT} branches — or
 *                            empty when they were
 * @param heuristicCommitted  Xids (base64) committed by an operator, not by their transaction manager
 * @param heuristicRolledBack Xids (base64) rolled back likewise
 * @param clockNote           how creation times were turned into instants, or why they could not be; empty when there
 *                            was nothing to convert
 */
public record Transactions(Reading<List<PreparedTransaction>> prepared, int preparedTotal, String detailSkipped,
        Reading<List<String>> heuristicCommitted, Reading<List<String>> heuristicRolledBack, String clockNote,
        Instant collectedAt)
{

    public boolean any()
    {
        return prepared.available() && !prepared.value().isEmpty();
    }

    public boolean detailRead()
    {
        return detailSkipped.isEmpty();
    }

    /**
     * Messages received from this address that prepared branches hold: counted on its queues as delivering, with no
     * consumer holding them. Zero when nothing was read — callers check {@link #prepared()} for that.
     */
    public long heldFrom(String address)
    {
        if (!prepared.available() || address == null)
        {
            return 0;
        }
        return prepared.value().stream().mapToLong(tx -> tx.heldByAddress().getOrDefault(address, 0L)).sum();
    }

    /** The prepared branches that touch this address, received from or sent to. */
    public List<PreparedTransaction> touching(String address)
    {
        if (!prepared.available() || address == null)
        {
            return List.of();
        }
        return prepared.value().stream().filter(tx -> tx.addresses().contains(address)).toList();
    }

    public int heuristicCount()
    {
        return heuristicCommitted.orElse(List.of()).size() + heuristicRolledBack.orElse(List.of()).size();
    }
}
