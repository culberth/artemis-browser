package com.culberth.tools.artemisbrowser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Phase 15 regression lab ({@code test-lab/}) writes to brokers; Artemis Browser must never carry any of it. The
 * lab is a separate Maven project, not a module, so neither its classes nor its build can reach this artifact.
 */
class LabIsolationTest
{

    @Test
    @DisplayName("The regression lab is not a module of this build and none of its classes are on this classpath")
    void labIsSeparate() throws Exception
    {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        assertFalse(pom.contains("<modules>"), "the root pom must not become a reactor that builds the lab");
        assertFalse(pom.contains("test-lab"), "the root pom must not reference the lab");
        assertFalse(pom.contains("artemis-regression-lab"), "the root pom must not depend on the lab");
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.culberth.tools.artemislab.LabApplication"));
    }
}
