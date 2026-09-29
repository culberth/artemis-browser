package com.culberth.tools.artemisbrowser.broker;

/**
 * Why a value is, or is not, there.
 *
 * <p>
 * The distinctions are the broker's, not ours, and they are narrower than they look. An unknown <em>operation</em> is
 * answered {@code AMQ229069: no operation}, and a denied one names the missing permission, so those two can be told
 * apart. An <em>attribute</em> the broker does not have, one on a resource that has gone, and one this user may not
 * read all come back as the same {@code Problem while retrieving attribute} — so {@link #UNAVAILABLE} is the honest
 * word for that, and nothing here claims more.
 */
public enum Availability
{
    /** Read, and the value is what the broker said. A zero here is a real zero. */
    AVAILABLE("available"),
    /** The broker has no such operation — usually an older or newer version than this read was written for. */
    UNSUPPORTED("not supported by this broker"),
    /** The broker answered, and refused this user. */
    DENIED("not permitted for this user"),
    /** An attribute the broker would not return: unsupported, gone, or denied — it does not say which. */
    UNAVAILABLE("not available from this broker"),
    /** Asked, and the answer could not be used: a rejected call, a timeout, or a value in an unexpected shape. */
    FAILED("could not be read"),
    /** Not asked at all — outside a limit, or skipped because something it depends on was missing. */
    NOT_COLLECTED("not collected");

    private final String label;

    Availability(String label)
    {
        this.label = label;
    }

    public String label()
    {
        return label;
    }
}
