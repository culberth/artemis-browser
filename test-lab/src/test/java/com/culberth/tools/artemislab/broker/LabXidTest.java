package com.culberth.tools.artemislab.broker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.jms.XAConnection;
import jakarta.jms.XASession;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import org.junit.jupiter.api.Test;

class LabXidTest
{
    @Test
    void absentBranchIsIdempotentButResolutionFailureIsNotSuccess() throws Exception
    {
        TargetGuard guard = mock(TargetGuard.class);
        XAConnection connection = mock(XAConnection.class);
        XASession session = mock(XASession.class);
        XAResource resource = mock(XAResource.class);
        when(guard.openXa()).thenReturn(connection);
        when(connection.createXASession()).thenReturn(session);
        when(session.getXAResource()).thenReturn(resource);
        LabXid xid = new LabXid("lab.run.xa-branch");
        doThrow(new XAException(XAException.XAER_NOTA)).when(resource).rollback(xid);
        assertDoesNotThrow(() -> xid.rollback(guard));
        verify(connection).close();
        verify(session).close();
        doThrow(new XAException(XAException.XAER_RMFAIL)).when(resource).rollback(xid);
        assertThrows(XAException.class, () -> xid.rollback(guard));
    }
}
