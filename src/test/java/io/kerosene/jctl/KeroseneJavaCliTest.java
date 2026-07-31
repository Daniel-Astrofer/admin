package io.kerosene.jctl;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class KeroseneJavaCliTest {

    // -- Root help parsing (root command has mixinStandardHelpOptions)

    @Test
    void helpReturnsZero() {
        assertEquals(0, new CommandLine(new KeroseneJavaCli()).execute("--help"));
    }

    // -- Output formatting tests

    @Test
    void outputJsonProducesCompactJson() throws Exception {
        String json = "{\"key\":\"value\",\"num\":42}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(json);
        String result = KeroseneJavaCli.formatOutput(body, "json");
        assertEquals("{\"key\":\"value\",\"num\":42}", result);
    }

    @Test
    void outputJsonPrettyProducesIndentedJson() throws Exception {
        String json = "{\"key\":\"value\",\"num\":42}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(json);
        String result = KeroseneJavaCli.formatOutput(body, "json-pretty");
        assertTrue(result.contains("\n"), "json-pretty should produce multiline output");
        assertTrue(result.contains("  "), "json-pretty should contain indentation");
    }

    @Test
    void outputTextProducesKeyValueSummary() throws Exception {
        String json = "{\"key\":\"value\",\"num\":42}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(json);
        String result = KeroseneJavaCli.formatOutput(body, "text");
        assertTrue(result.contains("key=value"));
        assertTrue(result.contains("num=42"));
    }

    @Test
    void outputTextForNestedObject() throws Exception {
        String json = "{\"outer\":{\"inner\":\"deep\"}}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(json);
        String result = KeroseneJavaCli.formatOutput(body, "text");
        assertTrue(result.contains("outer={"));
        assertTrue(result.contains("inner=deep"));
    }

    @Test
    void outputTextForArrayShowsLength() throws Exception {
        String json = "{\"items\":[1,2,3]}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(json);
        String result = KeroseneJavaCli.formatOutput(body, "text");
        assertTrue(result.contains("[3 items]"));
    }

    @Test
    void outputTextForNullValue() throws Exception {
        String json = "{\"nullable\":null}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(json);
        String result = KeroseneJavaCli.formatOutput(body, "text");
        assertTrue(result.contains("nullable=null"));
    }

    @Test
    void outputTextForNestedArray() throws Exception {
        String json = "{\"data\":{\"tags\":[\"a\",\"b\"]}}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(json);
        String result = KeroseneJavaCli.formatOutput(body, "text");
        assertTrue(result.contains("[2 items]"));
    }

    @Test
    void outputFormatsDifferAcrossModes() throws Exception {
        String json = "{\"key\":\"value\"}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(json);
        String compact = KeroseneJavaCli.formatOutput(body, "json");
        String pretty = KeroseneJavaCli.formatOutput(body, "json-pretty");
        String text = KeroseneJavaCli.formatOutput(body, "text");
        assertNotEquals(compact, pretty);
        assertNotEquals(compact, text);
        assertNotEquals(pretty, text);
    }

    @Test
    void formatOutputWithComplexJsonAllModesDiffer() throws Exception {
        String json = "{\"string\":\"hello\",\"number\":42,\"bool\":true,\"null_val\":null,\"arr\":[1,2],\"obj\":{\"nested\":\"val\"}}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(json);
        String jsonResult = KeroseneJavaCli.formatOutput(body, "json");
        String prettyResult = KeroseneJavaCli.formatOutput(body, "json-pretty");
        String textResult = KeroseneJavaCli.formatOutput(body, "text");
        assertNotEquals(jsonResult, prettyResult);
        assertNotEquals(jsonResult, textResult);
        assertNotEquals(prettyResult, textResult);
    }

    // -- Request ID tests

    @Test
    void defaultRequestIdMatchesPattern() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        String id1 = cli.requestId();
        assertTrue(id1.startsWith("jctl-"), "requestId should start with jctl-");
        assertDoesNotThrow(() -> UUID.fromString(id1.substring("jctl-".length())),
                "requestId suffix should be a valid UUID");
    }

    @Test
    void defaultRequestIdIsUnique() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        String id1 = cli.requestId();
        String id2 = cli.requestId();
        assertNotEquals(id1, id2, "auto-generated request IDs should be unique");
    }

    @Test
    void customRequestIdIsUsed() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        cli.requestId = "my-custom-id";
        assertEquals("my-custom-id", cli.requestId());
    }

    @Test
    void customRequestIdWithBlankIsIgnored() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        cli.requestId = "  ";
        String id = cli.requestId();
        assertTrue(id.startsWith("jctl-"), "blank custom requestId should be ignored");
    }

    // -- Timeout tests

    @Test
    void timeoutParsesCorrectly() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        new CommandLine(cli).execute("--timeout", "30", "--help");
        assertEquals(30, cli.timeout);
    }

    @Test
    void timeoutDefaultIsTen() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        new CommandLine(cli).execute("--help");
        assertEquals(10, cli.timeout);
    }

    // -- Verbose flag tests

    @Test
    void verboseFlagIsAccepted() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        new CommandLine(cli).execute("--verbose", "--help");
        assertTrue(cli.verbose);
    }

    @Test
    void verboseDefaultsToFalse() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        assertFalse(cli.verbose);
    }

    // -- Segment URL encoding tests

    @Test
    void segmentEncodesSlashAndSpace() {
        assertEquals("order%2Fwith%20space", KeroseneJavaCli.segment("order/with space"));
    }

    @Test
    void segmentEncodesSpace() {
        assertEquals("hello%20world", KeroseneJavaCli.segment("hello world"));
    }

    @Test
    void segmentEncodesUnicode() {
        assertEquals("%C3%A9", KeroseneJavaCli.segment("\u00e9"));
    }

    @Test
    void segmentEncodesSpecialCharacters() {
        assertEquals("a%26b%3Dc%25d", KeroseneJavaCli.segment("a&b=c%d"));
    }

    @Test
    void segmentPreservesSimpleStrings() {
        assertEquals("simple-id-123", KeroseneJavaCli.segment("simple-id-123"));
    }

    @Test
    void segmentEncodesPlusSign() {
        assertEquals("a%2Bb", KeroseneJavaCli.segment("a+b"));
    }

    // -- Endpoint validation tests

    @Test
    void missingEndpointThrows() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        Exception e = assertThrows(IllegalArgumentException.class, () -> cli.get("/test"));
        assertTrue(e.getMessage().contains("--endpoint") || e.getMessage().contains("--profile"));
    }

    // -- Command hierarchy verification

    @Test
    void ledgerSubcommandHasAccountAndJournal() {
        CommandLine cmd = new CommandLine(new KeroseneJavaCli());
        CommandLine ledger = cmd.getSubcommands().get("ledger");
        assertNotNull(ledger);
        assertTrue(ledger.getSubcommands().containsKey("account"));
        assertTrue(ledger.getSubcommands().containsKey("journal"));
    }

    @Test
    void p2pSubcommandHasOrderWithInspect() {
        CommandLine cmd = new CommandLine(new KeroseneJavaCli());
        CommandLine p2p = cmd.getSubcommands().get("p2p");
        assertNotNull(p2p);
        CommandLine order = p2p.getSubcommands().get("order");
        assertNotNull(order);
        assertTrue(order.getSubcommands().containsKey("inspect"));
    }

    @Test
    void onrampSubcommandHasOrderWithInspect() {
        CommandLine cmd = new CommandLine(new KeroseneJavaCli());
        CommandLine onramp = cmd.getSubcommands().get("onramp");
        assertNotNull(onramp);
        CommandLine order = onramp.getSubcommands().get("order");
        assertNotNull(order);
        assertTrue(order.getSubcommands().containsKey("inspect"));
    }

    @Test
    void reconciliationSubcommandHasStatus() {
        CommandLine cmd = new CommandLine(new KeroseneJavaCli());
        CommandLine reconciliation = cmd.getSubcommands().get("reconciliation");
        assertNotNull(reconciliation);
        assertTrue(reconciliation.getSubcommands().containsKey("status"));
    }

    @Test
    void providerSubcommandHasConnectionWithValidate() {
        CommandLine cmd = new CommandLine(new KeroseneJavaCli());
        CommandLine provider = cmd.getSubcommands().get("provider");
        assertNotNull(provider);
        CommandLine connection = provider.getSubcommands().get("connection");
        assertNotNull(connection);
        assertTrue(connection.getSubcommands().containsKey("validate"));
    }

    // -- Output flag parsing tests

    @Test
    void outputJsonParses() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        new CommandLine(cli).execute("--output", "json", "--help");
        assertEquals("json", cli.output);
    }

    @Test
    void outputJsonPrettyParses() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        new CommandLine(cli).execute("--output", "json-pretty", "--help");
        assertEquals("json-pretty", cli.output);
    }

    @Test
    void outputTextParses() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        new CommandLine(cli).execute("--output", "text", "--help");
        assertEquals("text", cli.output);
    }

    @Test
    void outputDefaultIsText() {
        KeroseneJavaCli cli = new KeroseneJavaCli();
        new CommandLine(cli).execute("--help");
        assertEquals("text", cli.output);
    }

    // -- Main class smoke test

    @Test
    void mainExecutesHelpSuccessfully() {
        assertEquals(0, new CommandLine(new KeroseneJavaCli()).execute("--help"));
    }

    // -- Profile tests

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
