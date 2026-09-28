package com.culberth.tools.artemisbrowser.broker;

import java.util.Locale;

/**
 * Messages added to and acknowledged on one queue per second, over the interval its {@link Rates} carries.
 *
 * <p>
 * "Acknowledged", not "out": a message can also leave by expiring or being killed, and those are counted separately.
 */
public record QueueRate(double inPerSecond, double ackedPerSecond)
{

    public String inText()
    {
        return text(inPerSecond);
    }

    public String ackedText()
    {
        return text(ackedPerSecond);
    }

    /** "0", "<0.1", "2.4", "130" — a rate, not a measurement to three decimals. */
    static String text(double perSecond)
    {
        if (perSecond <= 0)
        {
            return "0";
        }
        if (perSecond < 0.1)
        {
            return "<0.1";
        }
        return perSecond < 10 ? String.format(Locale.ROOT, "%.1f", perSecond) : String.valueOf(Math.round(perSecond));
    }
}
