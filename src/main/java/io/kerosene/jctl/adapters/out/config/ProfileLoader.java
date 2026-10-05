package io.kerosene.jctl.adapters.out.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** Reads named operator endpoint profiles without storing credentials in the profile. */
public final class ProfileLoader {
    /** Endpoint and environment settings resolved for one named profile.
     * @param environment environment label used for production safeguards
     * @param endpoint base URI of the service admin API
     */
    public record Profile(String environment, String endpoint) {}

    private ProfileLoader() {}

    /** Loads a profile from the configured file or the user's default config path.
     * @param name profile key (letters, digits, underscore and hyphen only)
     * @return resolved profile settings
     * @throws IOException when the selected file cannot be read
     */
    public static Profile load(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid profile name");
        }
        String configured = System.getenv("KEROSENE_PROFILES_FILE");
        Path path = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config", "kerosene", "profiles.toml")
                : Path.of(configured);
        return load(name, path);
    }

    /** Parses and validates a named profile from an explicit TOML file.
     * @param name profile key (letters, digits, underscore and hyphen only)
     * @param path TOML file containing a {@code profiles.<name>} table
     * @return endpoint and environment settings
     * @throws IOException when the file cannot be read
     */
    public static Profile load(String name, Path path) throws IOException {
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

    /** Requires a configured regular TLS file and forbids group/other access to a keystore.
     * @param property JVM system-property name containing the file path
     * @throws IOException when filesystem permissions cannot be inspected
     */
    public static void requirePrivateRegularFile(String property) throws IOException {
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
