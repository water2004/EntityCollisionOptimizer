package org.edtp.entitycollisionoptimizer.integration;

import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Shared record/compare boundary for every no-mod versus mod integration scenario. */
final class CrossProcessTrace {
    private static final String MODE_PROPERTY = "entity_collision_optimizer.integration.mode";
    private static final String TRACE_DIR_PROPERTY = "entity_collision_optimizer.integration.trace_dir";

    private CrossProcessTrace() {
    }

    static void verify(GameTestHelper helper, String scenario, byte[] actual) {
        String mode = System.getProperty(MODE_PROPERTY);
        String traceDirectory = System.getProperty(TRACE_DIR_PROPERTY);
        if (mode == null || traceDirectory == null) {
            throw new IllegalStateException("Integration tests require the integrationTest Gradle suite");
        }

        Path tracePath = Path.of(traceDirectory).resolve(scenario + ".bin");
        try {
            if ("record".equals(mode)) {
                Files.createDirectories(tracePath.getParent());
                Files.write(tracePath, actual);
                return;
            }
            if (!"compare".equals(mode)) {
                throw new IllegalStateException("Unknown integration trace mode: " + mode);
            }

            byte[] expected = Files.readAllBytes(tracePath);
            boolean matches = Arrays.equals(actual, expected);
            if (!matches) {
                Files.write(tracePath.resolveSibling(scenario + "-actual.bin"), actual);
            }
            helper.assertTrue(matches, mismatchMessage(scenario, expected, actual));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot access integration trace " + tracePath, failure);
        }
    }

    private static String mismatchMessage(String scenario, byte[] expected, byte[] actual) {
        int common = Math.min(expected.length, actual.length);
        int offset = 0;
        while (offset < common && expected[offset] == actual[offset]) {
            offset++;
        }
        return scenario + " trace differs at byte " + offset
                + " (vanilla length=" + expected.length
                + ", optimized length=" + actual.length + ")";
    }
}
