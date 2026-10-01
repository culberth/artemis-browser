package com.culberth.tools.artemislab.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemislab.LabException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScenarioCatalogTest
{

    private final ScenarioCatalog catalog = new ScenarioCatalog();

    @Test
    @DisplayName("The catalog's case ids are exactly the regression procedure's, so neither drifts from the other")
    void casesMatchTheProcedure() throws Exception
    {
        // Tests run from test-lab/; the procedure lives in the Browser repository's docs.
        String procedure = Files.readString(Path.of("../docs/Phase 15 regression procedure.md"),
                StandardCharsets.UTF_8);
        Matcher row = Pattern.compile("(?m)^\\| ([A-Z]\\d\\d) ").matcher(procedure);
        Set<String> documented = new LinkedHashSet<>();
        while (row.find())
        {
            documented.add(row.group(1));
        }
        Set<String> catalogued = catalog.cases().stream().map(ScenarioCatalog.Case::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(documented, catalogued);
    }

    @Test
    @DisplayName("Implemented recipes run; a catalogued recipe that is not implemented refuses to run")
    void onlyImplementedScenariosRun()
    {
        assertEquals(SmokeScenario.ID, catalog.runnable(SmokeScenario.ID).id());
        assertEquals("BASIC", catalog.runnable("BASIC").id());
        assertThrows(LabException.class, () -> catalog.runnable("HARNESS"));
        assertThrows(LabException.class, () -> catalog.runnable("NOPE"));
        assertTrue(catalog.knowsCase("E08"));
        assertFalse(catalog.knowsCase("Z99"));
    }

    @Test
    @DisplayName("Smoke bodies are deterministic and exactly the requested size")
    void deterministicBodies()
    {
        assertEquals(SmokeScenario.body("r1", 3, 100), SmokeScenario.body("r1", 3, 100));
        assertEquals(100, SmokeScenario.body("r1", 3, 100).length());
        assertEquals(0, SmokeScenario.body("r1", 3, 0).length());
        assertTrue(SmokeScenario.body("r1", 3, 100).startsWith("lab smoke r1 #3 "));
    }
}
