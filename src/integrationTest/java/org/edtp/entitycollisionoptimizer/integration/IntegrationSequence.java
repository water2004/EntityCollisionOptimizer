package org.edtp.entitycollisionoptimizer.integration;

/** Keeps integration scenarios ordered inside one GameTest environment batch. */
final class IntegrationSequence {
    private static boolean crammingComplete;

    private IntegrationSequence() {
    }

    static boolean isCrammingComplete() {
        return crammingComplete;
    }

    static void completeCramming() {
        crammingComplete = true;
    }
}
