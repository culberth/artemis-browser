package com.culberth.tools.artemisbrowser.broker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
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

    /** {@code //user:password@} in a URI. */
    private static final Pattern USER_INFO = Pattern.compile("(//[^/:@?#]+):[^@/?#]*@");

    /** One {@code key=value} parameter, after {@code ?}, {@code &} or {@code ;}. */
    private static final Pattern PARAMETER = Pattern.compile("([?&;])([^=&;?#]+)=([^&;#]*)");

    /**
     * A URI with any password in its user info, and the value of any secret-looking parameter, masked.
     *
     * <p>
     * A broker connection's {@code uri} is returned exactly as it was configured, and Artemis accepts credentials and
     * key-store passwords as URI parameters — {@code tcp://host:61616?password=...}. Nothing in the management reply
     * marks them, so they are found by the same key rule as settings.
     */
    public static String uri(String uri)
    {
        if (uri == null)
        {
            return null;
        }
        String masked = USER_INFO.matcher(uri).replaceAll("$1:" + Matcher.quoteReplacement(MASK) + "@");
        Matcher parameters = PARAMETER.matcher(masked);
        StringBuilder out = new StringBuilder();
        while (parameters.find())
        {
            String replacement = secret(parameters.group(2)) ? parameters.group(1) + parameters.group(2) + "=" + MASK
                    : parameters.group();
            parameters.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        parameters.appendTail(out);
        return out.toString();
    }
}
