package io.kerosene.jctl.adapters.out.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TlsContextFactoryTest {

    @Test
    void missingRequiredKeyStorePasswordThrows() {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> TlsContextFactory.productionContext(Map.of(), null, null));
        assertTrue(e.getMessage().contains("KEROSENE_KEYSTORE_PASSWORD"));
    }

    @Test
    void blankRequiredKeyStorePasswordThrows() {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> TlsContextFactory.productionContext(
                        Map.of("KEROSENE_KEYSTORE_PASSWORD", "  "), null, null));
        assertTrue(e.getMessage().contains("KEROSENE_KEYSTORE_PASSWORD"));
    }

    @Test
    void missingKeyStoreAndTrustStorePathThrows() {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> TlsContextFactory.productionContext(
                        Map.of("KEROSENE_KEYSTORE_PASSWORD", "testpass"), null, null));
        assertEquals("Missing JVM TLS property: javax.net.ssl.keyStore", e.getMessage());
    }
}
