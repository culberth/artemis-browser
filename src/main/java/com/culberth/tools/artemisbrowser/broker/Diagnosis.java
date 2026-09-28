package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * The diagnose page's findings, plus what it chose not to read and how it measured rates.
 *
 * <p>
 * How long a message has been in flight is only in each queue's delivering list, which has no paging, so the page reads
 * those lists within a budget. What it skips is said here rather than as a finding: a limit of this page is not a fault
 * on the broker, and a finding that appears on every busy broker is one nobody reads.
 *
 * @param inFlightQueuesNotRead queues whose in-flight messages were not examined for age
 * @param inFlightNotRead       how many in-flight messages those queues hold
 * @param measured              the rates the findings used, and whether the page had to wait to take them
 */
public record Diagnosis(List<Finding> findings, int inFlightQueuesNotRead, long inFlightNotRead, Measured measured)
{

    public Diagnosis(List<Finding> findings, int inFlightQueuesNotRead, long inFlightNotRead)
    {
        this(findings, inFlightQueuesNotRead, inFlightNotRead,
                new Measured(Rates.none("rates were not measured"), false));
    }

    public long stuckCount()
    {
        return findings.stream().filter(Finding::isStuck).count();
    }

    /**
     * @param sampled true when there was no recent reading in the session, so this page took two readings a few seconds
     *                apart itself — which is why it was slower
     */
    public record Measured(Rates rates, boolean sampled)
    {
    }
}
