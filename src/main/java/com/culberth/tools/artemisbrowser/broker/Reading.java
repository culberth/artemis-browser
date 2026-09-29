package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One value read from the broker, or the reason there is none, and when it was asked for.
 *
 * <p>
 * Exists so that a value the broker would not give is never shown as zero, false or "healthy". Before it, an attribute
 * that failed to parse became 0, and a disk at 0% used cannot trip a pressure warning — the kind of wrong answer this
 * tool is built to avoid. A {@link ConnectionLostException} is deliberately not caught by {@link #attempt}: a lost
 * connection is not one unavailable value but the end of the page, and is handled as such.
 *
 * @param detail what the broker said when there is no value; empty when there is one
 */
public record Reading<T>(Availability availability, T value, String detail, Instant collectedAt)
{

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public static <T> Reading<T> of(T value)
    {
        return new Reading<>(Availability.AVAILABLE, value, "", Instant.now());
    }

    public static <T> Reading<T> missing(Availability availability, String detail)
    {
        if (availability == Availability.AVAILABLE)
        {
            throw new IllegalArgumentException("A missing reading needs a reason other than AVAILABLE");
        }
        return new Reading<>(availability, null, detail == null ? "" : detail, Instant.now());
    }

    public static <T> Reading<T> notCollected(String why)
    {
        return missing(Availability.NOT_COLLECTED, why);
    }

    public static <T> Reading<T> failed(String why)
    {
        return missing(Availability.FAILED, why);
    }

    /** The same missing reading, retyped — a reason carries over whatever the value would have been. */
    public <U> Reading<U> absent()
    {
        if (available())
        {
            throw new IllegalStateException("This reading has a value");
        }
        return new Reading<>(availability, null, detail, collectedAt);
    }

    /** Runs one read, turning a refusal or failure into a reading that says so. A lost connection still propagates. */
    public static <T> Reading<T> attempt(Supplier<T> read)
    {
        try
        {
            return of(read.get());
        }
        catch (ManagementRefusal e)
        {
            return missing(e.availability(), e.getMessage());
        }
        catch (BrokerException e)
        {
            return failed(e.getMessage());
        }
    }

    public boolean available()
    {
        return availability == Availability.AVAILABLE;
    }

    public <U> Reading<U> map(Function<T, U> mapping)
    {
        return available() ? new Reading<>(availability, mapping.apply(value), detail, collectedAt) : absent();
    }

    public T orElse(T fallback)
    {
        return available() ? value : fallback;
    }

    /** "not permitted for this user", say — the short form for beside a missing value. */
    public String reason()
    {
        return availability.label();
    }

    /** The reason with the broker's own words after it, for a sentence that has room for both. */
    public String explained()
    {
        return detail.isBlank() ? reason() : reason() + " — " + detail;
    }

    public String collectedText()
    {
        return TIME.format(collectedAt);
    }
}
