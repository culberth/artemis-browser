package com.culberth.tools.artemislab.broker;

import java.nio.charset.StandardCharsets;
import javax.transaction.xa.Xid;

/** Exact, reproducible transaction identity persisted before a branch is started. */
public record LabXid(String name) implements Xid
{
    public LabXid
    {
        if (name.getBytes(StandardCharsets.UTF_8).length > Xid.MAXGTRIDSIZE)
            throw new IllegalArgumentException("XA name is too long");
    }

    @Override
    public int getFormatId()
    {
        return 4242;
    }

    @Override
    public byte[] getGlobalTransactionId()
    {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] getBranchQualifier()
    {
        return "lab-1".getBytes(StandardCharsets.UTF_8);
    }

    /** Resolve only this exact branch; XAER_NOTA means it is already absent. */
    public void rollback(TargetGuard guard) throws Exception
    {
        try (var connection = guard.openXa(); var session = connection.createXASession())
        {
            try
            {
                session.getXAResource().rollback(this);
            }
            catch (javax.transaction.xa.XAException e)
            {
                if (e.errorCode != javax.transaction.xa.XAException.XAER_NOTA)
                    throw e;
            }
        }
    }
}
