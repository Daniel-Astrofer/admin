package io.kerosene.jctl;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class TlsContextFactory {
    private TlsContextFactory() {}

    static SSLContext productionContext() throws Exception {
        String keyStorePath = System.getProperty("javax.net.ssl.keyStore");
        String trustStorePath = System.getProperty("javax.net.ssl.trustStore");
        char[] keyPassword = requiredSecret("KEROSENE_KEYSTORE_PASSWORD");
        char[] trustPassword = optionalSecret("KEROSENE_TRUSTSTORE_PASSWORD", keyPassword);
        try {
            KeyStore keys = loadPkcs12(Path.of(keyStorePath), keyPassword);
            KeyStore trust = loadPkcs12(Path.of(trustStorePath), trustPassword);
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

    private static KeyStore loadPkcs12(Path path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private static char[] requiredSecret(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing runtime credential: " + name);
        }
        return value.toCharArray();
    }

    private static char[] optionalSecret(String name, char[] fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback.clone() : value.toCharArray();
    }
}
