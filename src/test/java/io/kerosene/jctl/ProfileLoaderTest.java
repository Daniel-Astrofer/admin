package io.kerosene.jctl;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileLoaderTest {

    @Test
    void loadsValidProfile(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(
                path,
                """
                [profiles.production]
                environment = "production"
                core_endpoint = "https://core.example.onion"
                """);

        ProfileLoader.Profile profile = ProfileLoader.load("production", path);
        assertEquals("production", profile.environment());
        assertEquals("https://core.example.onion", profile.endpoint());
    }

    @Test
    void loadsProfileWithCustomEnvironment(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(
                path,
                """
                [profiles.staging]
                environment = "staging"
                core_endpoint = "https://staging.example.onion"
                """);

        ProfileLoader.Profile profile = ProfileLoader.load("staging", path);
        assertEquals("staging", profile.environment());
        assertEquals("https://staging.example.onion", profile.endpoint());
    }

    @Test
    void profileDefaultsToProductionWhenEnvironmentMissing(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(
                path,
                """
                [profiles.dev]
                core_endpoint = "https://dev.example.onion"
                """);

        ProfileLoader.Profile profile = ProfileLoader.load("dev", path);
        assertEquals("production", profile.environment());
    }

    @Test
    void missingProfileNameThrows(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(path, "");

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> ProfileLoader.load(null, path));
        assertTrue(e.getMessage().contains("Invalid profile name"));
    }

    @Test
    void invalidProfileNameThrows(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(path, "");

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> ProfileLoader.load("../evil", path));
        assertTrue(e.getMessage().contains("Invalid profile name"));
    }

    @Test
    void profileNameWithSpacesThrows(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(path, "");

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> ProfileLoader.load("bad name", path));
        assertTrue(e.getMessage().contains("Invalid profile name"));
    }

    @Test
    void malformedTomlFileThrows(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(path, "[[[invalid]]]");

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> ProfileLoader.load("production", path));
        assertTrue(e.getMessage().contains("Invalid profiles file"));
    }

    @Test
    void missingCoreEndpointThrows(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(
                path,
                """
                [profiles.production]
                environment = "production"
                """);

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> ProfileLoader.load("production", path));
        assertTrue(e.getMessage().contains("core_endpoint"));
    }

    @Test
    void blankCoreEndpointThrows(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(
                path,
                """
                [profiles.production]
                environment = "production"
                core_endpoint = ""
                """);

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> ProfileLoader.load("production", path));
        assertTrue(e.getMessage().contains("core_endpoint"));
    }

    @Test
    void nonexistentProfilesFileThrows(@TempDir Path directory) {
        Path path = directory.resolve("nonexistent.toml");
        assertThrows(IOException.class,
                () -> ProfileLoader.load("production", path));
    }

    @Test
    void absentProfileNameThrows(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("profiles.toml");
        Files.writeString(
                path,
                """
                [profiles.other]
                core_endpoint = "https://other.example.onion"
                """);

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> ProfileLoader.load("absent", path));
        assertTrue(e.getMessage().contains("absent"));
    }

    @Test
    void requirePrivateRegularFileValidatesKeyStorePermissions(@TempDir Path directory) throws Exception {
        Path keyStore = directory.resolve("keystore.p12");
        Files.createFile(keyStore);
        Files.setPosixFilePermissions(keyStore,
                PosixFilePermissions.fromString("rw-------"));
        String original = System.getProperty("javax.net.ssl.keyStore");
        try {
            System.setProperty("javax.net.ssl.keyStore", keyStore.toString());
            assertDoesNotThrow(() -> ProfileLoader.requirePrivateRegularFile("javax.net.ssl.keyStore"));
        } finally {
            if (original != null) {
                System.setProperty("javax.net.ssl.keyStore", original);
            } else {
                System.clearProperty("javax.net.ssl.keyStore");
            }
        }
    }

    @Test
    void requirePrivateRegularFileRejectsGroupAccess(@TempDir Path directory) throws Exception {
        Path keyStore = directory.resolve("keystore.p12");
        Files.createFile(keyStore);
        Files.setPosixFilePermissions(keyStore,
                PosixFilePermissions.fromString("rw-r-----"));
        String original = System.getProperty("javax.net.ssl.keyStore");
        try {
            System.setProperty("javax.net.ssl.keyStore", keyStore.toString());
            Exception e = assertThrows(IllegalArgumentException.class,
                    () -> ProfileLoader.requirePrivateRegularFile("javax.net.ssl.keyStore"));
            assertTrue(e.getMessage().contains("must not grant group/other access"));
        } finally {
            if (original != null) {
                System.setProperty("javax.net.ssl.keyStore", original);
            } else {
                System.clearProperty("javax.net.ssl.keyStore");
            }
        }
    }

    @Test
    void requirePrivateRegularFileThrowsForMissingProperty() {
        String original = System.getProperty("javax.net.ssl.keyStore");
        try {
            System.clearProperty("javax.net.ssl.keyStore");
            Exception e = assertThrows(IllegalArgumentException.class,
                    () -> ProfileLoader.requirePrivateRegularFile("javax.net.ssl.keyStore"));
            assertTrue(e.getMessage().contains("Missing JVM mTLS property"));
        } finally {
            if (original != null) {
                System.setProperty("javax.net.ssl.keyStore", original);
            }
        }
    }

    @Test
    void requirePrivateRegularFileThrowsForNonexistentFile() {
        String original = System.getProperty("javax.net.ssl.keyStore");
        try {
            System.setProperty("javax.net.ssl.keyStore", "/nonexistent/keystore.p12");
            Exception e = assertThrows(IllegalArgumentException.class,
                    () -> ProfileLoader.requirePrivateRegularFile("javax.net.ssl.keyStore"));
            assertTrue(e.getMessage().contains("not a regular file"));
        } finally {
            if (original != null) {
                System.setProperty("javax.net.ssl.keyStore", original);
            } else {
                System.clearProperty("javax.net.ssl.keyStore");
            }
        }
    }
}
