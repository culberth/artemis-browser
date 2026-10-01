package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import java.util.List;
import java.util.Map;

/**
 * The implementation behind a runnable catalog card. Recipes are deterministic: the same parameters give the same
 * messages, bodies and properties, so a run's results can be compared with another's.
 */
public interface Recipe
{

    /** The catalog card id it implements. */
    String id();

    /** Its form parameters; most recipes are fixed presets and take none. */
    default List<Param> params(LabLimits limits)
    {
        return List.of();
    }

    /** Prepares the fixture and asserts it from the broker; returns a one-line outcome. */
    String run(Fixture fixture, Map<String, Integer> params) throws Exception;

    /** Follow-up actions on what {@link #run} prepared, e.g. one more rollback; available once it has run. */
    default List<Step> steps(LabLimits limits)
    {
        return List.of();
    }

    /** Runs one of {@link #steps}; returns a one-line outcome. */
    default String step(Fixture fixture, String stepId, Map<String, Integer> params) throws Exception
    {
        throw new LabException(id() + " has no step " + stepId + ".");
    }

    /**
     * A follow-up action.
     *
     * @param id     form path segment
     * @param label  button text
     * @param note   what it does and what to look for
     * @param params its form parameters
     */
    record Step(String id, String label, String note, List<Param> params)
    {
    }

    /**
     * An integer form parameter, validated server-side against its range.
     *
     * @param name         form field
     * @param label        shown beside it
     * @param defaultValue used when the form leaves it out
     * @param min          inclusive
     * @param max          inclusive
     */
    record Param(String name, String label, int defaultValue, int min, int max)
    {
    }
}
