package com.culberth.tools.artemislab.run;

import java.time.Instant;

/**
 * One line of a run's action log. A job writes {@link Outcome#STARTED} and then exactly one terminal line with the same
 * job id; a STARTED with no terminal line after a lab restart becomes {@link Outcome#INTERRUPTED}.
 *
 * @param at      when
 * @param jobId   the job, or blank for an immediate action
 * @param action  what was attempted
 * @param outcome what happened
 * @param detail  the specifics: counts, ids, the broker's words
 */
public record ActionRecord(Instant at, String jobId, String action, Outcome outcome, String detail)
{

    public enum Outcome
    {
        STARTED, SUCCEEDED, FAILED, CANCELLED, REFUSED, INTERRUPTED,
        /** An automatic fixture assertion held. Not a Browser test result. */
        ASSERTION_PASSED,
        /** An automatic fixture assertion did not hold by its deadline. */
        ASSERTION_FAILED;

        public boolean terminal()
        {
            return this != STARTED;
        }
    }

    public static ActionRecord now(String jobId, String action, Outcome outcome, String detail)
    {
        return new ActionRecord(Instant.now(), jobId == null ? "" : jobId, action, outcome,
                detail == null ? "" : detail);
    }
}
