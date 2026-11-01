package com.faforever.iceadapter.it;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.TestInfo;

/** Paths handed over by the Gradle {@code integrationTest} task. */
final class IntegrationTestSupport {

    private IntegrationTestSupport() {}

    static Path iceAdapterJar() {
        return existingFile("iceAdapter.jar");
    }

    static Path mockGameJar() {
        return existingFile("mockGame.jar");
    }

    /** A per-test directory under {@code build/integration-test-logs}, kept for CI to upload. */
    static Path logDir(TestInfo testInfo) {
        String base = System.getProperty("integrationTest.logDir", "build/integration-test-logs");
        return Path.of(
                base,
                testInfo.getTestClass().orElseThrow().getSimpleName(),
                testInfo.getTestMethod().orElseThrow().getName());
    }

    private static Path existingFile(String property) {
        String value = System.getProperty(property);
        if (value == null || !Files.isRegularFile(Path.of(value))) {
            throw new IllegalStateException("System property " + property + " must point to a file (was " + value
                    + "); run ./gradlew" + " :ice-adapter:integrationTest");
        }
        return Path.of(value);
    }
}
