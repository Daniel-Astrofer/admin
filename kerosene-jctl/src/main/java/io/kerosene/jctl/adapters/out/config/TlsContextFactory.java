package io.kerosene.jctl.adapters.out.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Builds the production mutual-TLS context from JVM paths and environment passwords. */
public final class TlsContextFactory {
    private TlsContextFactory() {}

    /** Loads production TLS material from process configuration.
     * @return initialized TLS 1.3 mutual-authentication context
     * @throws Exception if a property, secret, store, key manager, or trust manager is invalid
     */
    public static SSLContext productionContext() throws Exception {
        return productionContext(
                System.getenv(),
                System.getProperty("javax.net.ssl.keyStore"),
                System.getProperty("javax.net.ssl.trustStore"));
    }

    /** Builds TLS 1.3 managers and clears password buffers after loading key material. */
    static SSLContext productionContext(
            Map<String, String> environment, String keyStorePath, String trustStorePath) throws Exception {
        char[] keyPassword = requiredSecret("KEROSENE_KEYSTORE_PASSWORD", environment);
        char[] trustPassword = optionalSecret("KEROSENE_TRUSTSTORE_PASSWORD", keyPassword, environment);
        try {
            KeyStore keys = loadPkcs12(requiredPath("javax.net.ssl.keyStore", keyStorePath), keyPassword);
            KeyStore trust = loadPkcs12(requiredPath("javax.net.ssl.trustStore", trustStorePath), trustPassword);
            KeyManagerFactory keyManagers =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, keyPassword);
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), new SecureRandom());
            return context;
        } finally {
            Arrays.fill(keyPassword, '\0');
            Arrays.fill(trustPassword, '\0');
        }
    }

    /** Converts a required JVM TLS path property into a filesystem path. */
    private static Path requiredPath(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing JVM TLS property: " + property);
        }
        return Path.of(value);
    }

    /** Reads one PKCS#12 store and closes its input stream. */
    private static KeyStore loadPkcs12(Path path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    /** Requires a nonblank environment secret and returns a mutable character copy. */
    private static char[] requiredSecret(String name, Map<String, String> environment) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing runtime credential: " + name);
        }
        return value.toCharArray();
    }

    /** Uses an optional secret or returns a separate copy of the fallback buffer. */
    private static char[] optionalSecret(
            String name, char[] fallback, Map<String, String> environment) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback.clone() : value.toCharArray();
    }
}
