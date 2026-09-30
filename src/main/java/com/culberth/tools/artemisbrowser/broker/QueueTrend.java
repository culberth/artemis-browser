package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One queue's readings this session, as intervals, and what they say about change.
 *
 * <p>
 * Consecutive readings are joined into an interval only when they are measurements of the same thing. A broker restart
 * in between (from its uptime), a different queue id (deleted and made again), or a counter that went back (a reset)
 * each make a <em>break</em> instead: shown, and never averaged across. A long wait between readings is a <em>gap</em>:
 * its rates are averages over time nobody watched, and it says so. A reading the queue was missing from is counted, not
 * treated as zero.
 *
 * <p>
 * Acknowledgments are kept apart from expiry and killing throughout: a queue emptying by expiry is not being consumed.
 */
public final class QueueTrend
{

    /** An interval this much longer than the spacing had readings missed in between: pages were closed. */
    static final int GAP_FACTOR = 4;

    public enum Kind
    {
        MEASURED, RESTARTED, RECREATED, RESET
    }

    /**
     * Between two readings of the queue.
     *
     * @param missed readings taken in between that did not list this queue
     * @param gap    far longer between than the spacing — its rates average over time that was not observed
     */
    public record Interval(Kind kind, long from, long to, long depthFrom, long depthTo, double inPerSecond,
            double ackedPerSecond, long expired, long killed, int consumersFrom, int consumersTo, int missed,
            boolean gap)
    {

        public boolean measured()
        {
            return kind == Kind.MEASURED;
        }

        public String fromText()
        {
            return Trends.time(from);
        }

        public String toText()
        {
            return Trends.time(to);
        }

        public String lengthText()
        {
            return AddressDetail.ageText(to - from);
        }

        public String inText()
        {
            return QueueRate.text(inPerSecond);
        }

        public String ackedText()
        {
            return QueueRate.text(ackedPerSecond);
        }

        /** Why this interval is not a measurement, in words for the table. */
        public String breakText()
        {
            return switch (kind)
            {
                case RESTARTED -> "the broker restarted";
                case RECREATED -> "the queue was deleted and created again";
                case RESET -> "its counters went back";
                case MEASURED -> null;
            };
        }
    }

    private final List<TrendPoint> points;
    private final Trends trends;
    private final List<Interval> intervals;

    QueueTrend(List<TrendPoint> points, Trends trends)
    {
        this.points = points;
        this.trends = trends;
        this.intervals = build(points, trends);
    }

    private static List<Interval> build(List<TrendPoint> points, Trends trends)
    {
        List<Interval> result = new ArrayList<>();
        for (int i = 1; i < points.size(); i++)
        {
            TrendPoint before = points.get(i - 1);
            TrendPoint after = points.get(i);
            Kind kind = kind(before, after);
            long millis = Math.max(1, after.takenAt() - before.takenAt());
            double seconds = millis / 1000.0;
            boolean measured = kind == Kind.MEASURED;
            result.add(new Interval(kind, before.takenAt(), after.takenAt(), before.depth(), after.depth(),
                    measured ? (after.added() - before.added()) / seconds : 0,
                    measured ? (after.acked() - before.acked()) / seconds : 0,
                    measured ? after.expired() - before.expired() : 0, measured ? after.killed() - before.killed() : 0,
                    before.consumers(), after.consumers(), trends.readingsBetween(before.takenAt(), after.takenAt()),
                    millis > GAP_FACTOR * trends.spacingMillis()));
        }
        return result;
    }

    private static Kind kind(TrendPoint before, TrendPoint after)
    {
        if (after.epoch() != before.epoch())
        {
            return Kind.RESTARTED;
        }
        if (before.queueId() >= 0 && after.queueId() >= 0 && before.queueId() != after.queueId())
        {
            return Kind.RECREATED;
        }
        if (after.added() < before.added() || after.acked() < before.acked() || after.expired() < before.expired()
                || after.killed() < before.killed())
        {
            return Kind.RESET;
        }
        return Kind.MEASURED;
    }

    public List<TrendPoint> points()
    {
        return points;
    }

    public List<Interval> intervals()
    {
        return intervals;
    }

    /** The latest {@code count} intervals, newest first — for a table. */
    public List<Interval> recent(int count)
    {
        List<Interval> newestFirst = new ArrayList<>(intervals).reversed();
        return newestFirst.subList(0, Math.min(count, newestFirst.size()));
    }

    /** The intervals since the latest break — the only stretch whose ends may be compared. */
    List<Interval> current()
    {
        int start = 0;
        for (int i = 0; i < intervals.size(); i++)
        {
            if (!intervals.get(i).measured())
            {
                start = i + 1;
            }
        }
        return intervals.subList(start, intervals.size());
    }

    /** At least two readings of the same queue with no break between: something can be said about change. */
    public boolean comparable()
    {
        return !current().isEmpty();
    }

    /** Depth now minus depth at the start of the current stretch, or null when there is no stretch. */
    public Long depthChange()
    {
        List<Interval> current = current();
        return current.isEmpty() ? null : current.get(current.size() - 1).depthTo() - current.get(0).depthFrom();
    }

    /** "+120", "−30", "±0", or null. */
    public String depthChangeText()
    {
        Long change = depthChange();
        if (change == null)
        {
            return null;
        }
        return change > 0 ? "+" + change : change < 0 ? "−" + (-change) : "±0";
    }

    /** "12m 3s": how long the current stretch covers. */
    public String spanText()
    {
        List<Interval> current = current();
        return current.isEmpty() ? null
                : AddressDetail.ageText(current.get(current.size() - 1).to() - current.get(0).from());
    }

    /** "grew from 10 to 130 over 12m 3s (since 10:02:15)", or null with nothing to compare. */
    public String backlog()
    {
        List<Interval> current = current();
        if (current.isEmpty())
        {
            return null;
        }
        Interval first = current.get(0);
        Interval last = current.get(current.size() - 1);
        String span = " over " + spanText() + " (since " + first.fromText() + ")";
        if (last.depthTo() > first.depthFrom())
        {
            return "The backlog grew from " + first.depthFrom() + " to " + last.depthTo() + span + ".";
        }
        if (last.depthTo() < first.depthFrom())
        {
            return "The backlog shrank from " + first.depthFrom() + " to " + last.depthTo() + span + ".";
        }
        return "The backlog held at " + last.depthTo() + span + ".";
    }

    /**
     * When acknowledgments stopped or resumed, from the latest interval where that changed; or that none happened in
     * any interval while messages waited. Null when acknowledgments ran throughout, or there is too little to say.
     */
    public String consumption()
    {
        List<Interval> current = current();
        for (int i = current.size() - 1; i > 0; i--)
        {
            boolean now = current.get(i).ackedPerSecond() > 0;
            boolean before = current.get(i - 1).ackedPerSecond() > 0;
            if (now != before)
            {
                Interval changed = current.get(i);
                return (now ? "Acknowledgments resumed" : "Acknowledgments stopped") + " between " + changed.fromText()
                        + " and " + changed.toText() + ".";
            }
        }
        boolean anyWaiting = current.stream().anyMatch(interval -> interval.depthTo() > 0);
        if (!current.isEmpty() && anyWaiting && current.stream().allMatch(interval -> interval.ackedPerSecond() == 0))
        {
            return "Nothing was acknowledged in any interval since " + current.get(0).fromText()
                    + ", with messages waiting.";
        }
        return null;
    }

    /** Messages expired or killed in the latest interval — leaving without being consumed, now. Null when none. */
    public String removalsNow()
    {
        List<Interval> current = current();
        if (current.isEmpty())
        {
            return null;
        }
        Interval last = current.get(current.size() - 1);
        if (last.expired() == 0 && last.killed() == 0)
        {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (last.expired() > 0)
        {
            parts.add(last.expired() + " expired");
        }
        if (last.killed() > 0)
        {
            parts.add(last.killed() + " killed");
        }
        return String.join(" and ", parts) + " between " + last.fromText() + " and " + last.toText()
                + " — leaving without being consumed, and not counted as acknowledged.";
    }

    /** The latest change in how many consumers were attached, or null. */
    public String consumers()
    {
        for (Interval interval : current().reversed())
        {
            if (interval.consumersFrom() != interval.consumersTo())
            {
                return "Consumers went from " + interval.consumersFrom() + " to " + interval.consumersTo() + " between "
                        + interval.fromText() + " and " + interval.toText() + ".";
            }
        }
        return null;
    }

    /** The latest break, in words, or null — so nobody reads the stretch before it as continuous with the one after. */
    public String lastBreak()
    {
        for (Interval interval : intervals.reversed())
        {
            if (!interval.measured())
            {
                return "Between " + interval.fromText() + " and " + interval.toText() + " " + interval.breakText()
                        + "; figures before that are not compared with those after.";
            }
        }
        return null;
    }

    /**
     * The depth line as SVG polyline point lists, one per unbroken stretch, across this session's whole window so every
     * queue's line shares a time axis. A stretch of one reading is drawn as a short tick.
     */
    public List<String> polylines(int width, int height)
    {
        long from = trends.firstAt();
        long span = Math.max(1, trends.lastAt() - from);
        long max = Math.max(1, points.stream().mapToLong(TrendPoint::depth).max().orElse(1));
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int inLine = 0;
        for (int i = 0; i < points.size(); i++)
        {
            if (i > 0 && intervals.get(i - 1).kind() != Kind.MEASURED)
            {
                lines.add(close(line, inLine));
                line = new StringBuilder();
                inLine = 0;
            }
            TrendPoint point = points.get(i);
            double x = (point.takenAt() - from) * (double) (width - 1) / span;
            double y = (height - 1) - point.depth() * (double) (height - 2) / max;
            line.append(line.isEmpty() ? "" : " ").append(format(x)).append(',').append(format(y));
            inLine++;
        }
        lines.add(close(line, inLine));
        return lines;
    }

    private static String close(StringBuilder line, int count)
    {
        if (count == 1)
        {
            String[] xy = line.toString().split(",");
            return line + " " + format(Double.parseDouble(xy[0]) + 1) + "," + xy[1];
        }
        return line.toString();
    }

    private static String format(double value)
    {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
