package com.culberth.tools.artemislab.run;

import java.time.Instant;

/**
 * A regression case's result in one run. The fixture result (did the lab produce the condition?) and the Browser result
 * (did Artemis Browser show it correctly?) are recorded separately: a successful seed is not a Browser pass.
 *
 * @param caseId     a procedure case id, e.g. {@code M01}
 * @param fixture    the fixture's result
 * @param browser    the Browser observation's result
 * @param notes      actual result, evidence links, issue reference
 * @param recordedAt when
 */
public record CaseResult(String caseId, Status fixture, Status browser, String notes, Instant recordedAt)
{

    public enum Status
    {
        NOT_RUN, PASS, FAIL, BLOCKED, DEFERRED
    }

    /** Pass requires both. */
    public boolean passed()
    {
        return fixture == Status.PASS && browser == Status.PASS;
    }
}
