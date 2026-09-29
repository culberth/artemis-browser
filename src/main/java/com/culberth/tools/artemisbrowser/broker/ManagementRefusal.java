package com.culberth.tools.artemisbrowser.broker;

/**
 * The broker answered a management request, and the answer was no — with the reason it gave, as far as it gave one.
 *
 * <p>
 * A {@link BrokerException}, so every page that already shows a broker error beside what it was building still does.
 * What it adds is {@link #availability()}, so a page that can go on without this one value says why it is missing
 * rather than failing, or worse, showing zero. The wording each reason is recognised by is in
 * {@code .claude/memory.md}, checked on 2.44.0 and 2.55.0.
 */
public class ManagementRefusal extends BrokerException
{

    private final Availability availability;

    public ManagementRefusal(Availability availability, String message)
    {
        super(message);
        this.availability = availability;
    }

    public ManagementRefusal(Availability availability, String message, Throwable cause)
    {
        super(message, cause);
        this.availability = availability;
    }

    public Availability availability()
    {
        return availability;
    }
}
