package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scenario catalog, versioned, from {@code catalog/scenarios.json}: one card per recipe alias in the regression
 * procedure, each naming the procedure case ids it serves. A card is runnable only once its recipe is implemented;
 * until then it documents what the recipe will be, and a run cannot claim it.
 *
 * <p>
 * Case ids are the procedure's and stay stable. {@code ScenarioCatalogTest} checks the catalog's case list against the
 * procedure document itself.
 */
@Component
public class ScenarioCatalog
{

    private final Catalog catalog;

    public ScenarioCatalog()
    {
        this(new ClassPathResource("catalog/scenarios.json"));
    }

    ScenarioCatalog(ClassPathResource resource)
    {
        try (InputStream in = resource.getInputStream())
        {
            this.catalog = JsonMapper.builder().build().readValue(in, Catalog.class);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Cannot read the scenario catalog", e);
        }
        validate(catalog);
    }

    public String revision()
    {
        return catalog.revision();
    }

    public List<Scenario> scenarios()
    {
        return catalog.scenarios();
    }

    public List<Case> cases()
    {
        return catalog.cases();
    }

    public Optional<Scenario> find(String id)
    {
        return catalog.scenarios().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public Scenario runnable(String id)
    {
        Scenario scenario = find(id).orElseThrow(() -> new LabException("No scenario " + id + " in the catalog."));
        if (!scenario.runnable())
        {
            throw new LabException("Scenario " + id + " is catalogued but not implemented yet (" + scenario.increment()
                    + "). Nothing was run.");
        }
        return scenario;
    }

    public boolean knowsCase(String caseId)
    {
        return catalog.cases().stream().anyMatch(c -> c.id().equals(caseId));
    }

    /** Cases no scenario serves yet. */
    public List<Case> unserved()
    {
        Set<String> served = new LinkedHashSet<>();
        catalog.scenarios().forEach(s -> served.addAll(s.cases()));
        return catalog.cases().stream().filter(c -> !served.contains(c.id())).toList();
    }

    public List<Case> casesOf(Scenario scenario)
    {
        return catalog.cases().stream().filter(c -> scenario.cases().contains(c.id())).toList();
    }

    private static void validate(Catalog catalog)
    {
        Set<String> ids = new LinkedHashSet<>();
        for (Case c : catalog.cases())
        {
            if (!ids.add(c.id()))
            {
                throw new IllegalStateException("Duplicate case id in the catalog: " + c.id());
            }
        }
        for (Scenario scenario : catalog.scenarios())
        {
            for (String caseId : scenario.cases())
            {
                if (!ids.contains(caseId))
                {
                    throw new IllegalStateException("Scenario " + scenario.id() + " names unknown case " + caseId);
                }
            }
        }
    }

    record Catalog(String revision, List<Scenario> scenarios, List<Case> cases)
    {
    }

    /**
     * @param id        the recipe alias, e.g. {@code BASIC}
     * @param title     short name
     * @param increment the Phase 15 increment that implements it
     * @param runnable  whether the lab can run it today
     * @param summary   what it prepares
     * @param expected  what the lab asserts and what to look for in Browser
     * @param cases     procedure case ids it serves
     */
    public record Scenario(String id, String title, String increment, boolean runnable, String summary, String expected,
            List<String> cases)
    {
    }

    /**
     * @param id    procedure case id
     * @param title its feature name in the procedure
     * @param group the procedure section
     */
    public record Case(String id, String title, String group)
    {
    }
}
