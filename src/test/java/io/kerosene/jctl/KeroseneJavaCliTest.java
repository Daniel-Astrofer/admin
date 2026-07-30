package io.kerosene.jctl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class KeroseneJavaCliTest {
    @Test
    void helpAndNestedCommandsParseWithoutCredentials() {
        assertEquals(0, new CommandLine(new KeroseneJavaCli()).execute("--help"));
        assertEquals("order%2Fwith%20space", KeroseneJavaCli.segment("order/with space"));
    }
}
