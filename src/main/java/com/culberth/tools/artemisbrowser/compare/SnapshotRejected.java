package com.culberth.tools.artemisbrowser.compare;

/**
 * A snapshot file, or a pair of them, that cannot be compared — with a sentence saying why that the page shows as it
 * is. Never a partial comparison: an unreadable or mismatched file compares nothing rather than something misleading.
 */
public class SnapshotRejected extends RuntimeException
{

    public SnapshotRejected(String message)
    {
        super(message);
    }
}
