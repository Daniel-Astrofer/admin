package io.kerosene.jctl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

final class ProfileLoader {
    record Profile(String environment, String endpoint) {}

    private ProfileLoader() {}

    static Profile load(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid profile name");
        }
        String configured = System.getenv("KEROSENE_PROFILES_FILE");
        Path path = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config", "kerosene", "profiles.toml")
                : Path.of(configured);
        return load(name, path);
    }

    static Profile load(String name, Path path) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid profile name");
        }
        TomlParseResult result = Toml.parse(path);
        if (result.hasErrors()) {
            throw new IllegalArgumentException("Invalid profiles file: " + result.errors());
        }
        TomlTable table = result.getTable("profiles." + name);
        if (table == null) {
            throw new IllegalArgumentException("Profile is absent: " + name);
        }
        String endpoint = table.getString("core_endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Profile has no core_endpoint: " + name);
        }
        return new Profile(table.getString("environment", () -> "production"), endpoint);
    }

    static void requirePrivateRegularFile(String property) throws IOException {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing JVM mTLS property: " + property);
        }
        Path path = Path.of(value);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("mTLS credential is not a regular file: " + path);
        }
        if ("javax.net.ssl.keyStore".equals(property)
                && Files.getPosixFilePermissions(path).stream()
                        .anyMatch(permission -> permission.name().startsWith("GROUP_")
                                || permission.name().startsWith("OTHERS_"))) {
            throw new IllegalArgumentException("mTLS keyStore must not grant group/other access");
        }
    }
}
