package com.culberth.tools.artemisbrowser.broker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks values whose key names a secret, for anything free-form that leaves this tool in an incident snapshot.
 *
 * <p>
 * Most of what a snapshot carries is built field by field and holds no secret by construction — acceptors keep only
 * name, protocols, host and port, never their {@code keyStorePassword}. Settings maps are the exception: the broker
 * decides their keys, and a later version, a plugin or a connector can add one that holds a credential. So any key that
 * looks like one is masked, whatever its value, and the snapshot says how many were.
 */
public final class Redaction
{

    /** Key fragments that mark a credential. Deliberately broad: a masked harmless value costs nothing. */
    static final Pattern SECRET_KEY = Pattern
            .compile("(?i).*(password|passwd|secret|token|credential|passphrase|private[-_.]?key|api[-_.]?key|auth).*");

    public static final String MASK = "[redacted]";

    private Redaction()
    {
    }

    public static boolean secret(String key)
    {
        return key != null && SECRET_KEY.matcher(key).matches();
    }

    /** A copy with every secret-looking key's value masked; order kept. */
    public static Map<String, String> redact(Map<String, String> values)
    {
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, secret(key) ? MASK : value));
        return copy;
    }

    public static int count(Map<String, String> values)
    {
        return (int) values.keySet().stream().filter(Redaction::secret).count();
    }
}
