package io.kerosene.jctl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class KeroseneJavaCliTest {
    @Test
    void helpAndNestedCommandsParseWithoutCredentials() {
        assertEquals(0, new CommandLine(new KeroseneJavaCli()).execute("--help"));
        assertEquals("order%2Fwith%20space", KeroseneJavaCli.segment("order/with space"));
    }

    @Test
    void profileContainsEndpointsButNoCredentials(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(
                path,
                """
                [profiles.production]
                environment = "production"
                core_endpoint = "https://core.example.onion"
                """);

        var profile = ProfileLoader.load("production", path);
        assertEquals("production", profile.environment());
        assertEquals("https://core.example.onion", profile.endpoint());
    }
}
