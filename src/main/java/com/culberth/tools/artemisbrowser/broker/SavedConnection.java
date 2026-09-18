package com.culberth.tools.artemisbrowser.broker;

/**
 * A remembered broker location.
 *
 * <p>
 * There is no password field, and there must never be one. Saved connections exist to stop you retyping a hostname, not
 * to hold credentials — the whole design of {@link BrokerSession} is that the password is used once and dropped, and a
 * file on disk would undo that completely.
 */
public record SavedConnection(String label, String host, int port, String username)
{

    /** Stable identity for a saved entry: the same broker and user is the same entry. */
    public String key()
    {
        return username + "@" + host + ":" + port;
    }

    public String displayName()
    {
        return label == null || label.isBlank() ? key() : label + " (" + key() + ")";
    }
}
