package com.culberth.tools.artemislab.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.scenario.Fixture;
import com.culberth.tools.artemislab.scenario.Recipe;
import com.culberth.tools.artemislab.scenario.ScenarioCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunServiceTest
{

    private static final List<Recipe.Param> PARAMS = List.of(new Recipe.Param("count", "Messages", 10, 1, 1000),
            new Recipe.Param("bodyBytes", "Body bytes", 100, 0, 262144));

    @Test
    @DisplayName("Missing parameters take their defaults; extra form fields are ignored")
    void defaults()
    {
        Map<String, Integer> values = RunService.parameters(PARAMS, Map.of("_csrf", "x", "token", "t"));
        assertEquals(Map.of("count", 10, "bodyBytes", 100), values);
        assertEquals(500, RunService.parameters(PARAMS, Map.of("count", " 500 ")).get("count"));
    }

    @Test
    @DisplayName("A parameter outside its range or not a number is refused, never clamped")
    void refusesBadValues()
    {
        assertThrows(LabException.class, () -> RunService.parameters(PARAMS, Map.of("count", "0")));
        assertThrows(LabException.class, () -> RunService.parameters(PARAMS, Map.of("count", "1001")));
        assertThrows(LabException.class, () -> RunService.parameters(PARAMS, Map.of("bodyBytes", "-1")));
        LabException text = assertThrows(LabException.class,
                () -> RunService.parameters(PARAMS, Map.of("count", "ten")));
        assertTrue(text.getMessage().contains("whole number"));
    }

    @Test
    @DisplayName("The catalog's runnable cards and the implemented recipes must match exactly")
    void registryMatchesCatalog()
    {
        ScenarioCatalog catalog = new ScenarioCatalog();
        List<Recipe> all = new ArrayList<>();
        for (String id : List.of("LAB-SMOKE", "BASIC", "BODIES", "SEARCH"))
        {
            all.add(recipe(id));
        }
        assertEquals(4, RunService.registry(all, catalog).size());

        assertThrows(IllegalStateException.class, () -> RunService.registry(all.subList(0, 3), catalog),
                "a runnable card with no recipe");
        List<Recipe> extra = new ArrayList<>(all);
        extra.add(recipe("DELIVERY"));
        assertThrows(IllegalStateException.class, () -> RunService.registry(extra, catalog),
                "a recipe the catalog calls unimplemented");
        List<Recipe> twice = new ArrayList<>(all);
        twice.add(recipe("BASIC"));
        assertThrows(IllegalStateException.class, () -> RunService.registry(twice, catalog));
    }

    @Test
    @DisplayName("The send manifest keeps its earliest entries, counts the rest, and adds every body's bytes")
    void sentIsBounded()
    {
        RunManifest run = RunManifest.open("r1", "rev", Instant.now(), "UTC", "", null);
        List<SentMessage> batch = new ArrayList<>();
        for (int i = 1; i <= RunManifest.MAX_SENT + 3; i++)
        {
            batch.add(new SentMessage("q", i, "text", "ID:" + i, 10, ""));
        }
        run = run.withSent(batch);

        assertEquals(RunManifest.MAX_SENT, run.sent().size());
        assertEquals(3, run.droppedSent());
        assertEquals(10L * (RunManifest.MAX_SENT + 3), run.generatedBytes());
        assertEquals(1, run.sent().get(0).seq());
    }

    private static Recipe recipe(String id)
    {
        return new Recipe()
        {
            @Override
            public String id()
            {
                return id;
            }

            @Override
            public String run(Fixture fixture, Map<String, Integer> params)
            {
                return "";
            }
        };
    }
}
