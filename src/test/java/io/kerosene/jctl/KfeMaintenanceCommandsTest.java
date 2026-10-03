package io.kerosene.jctl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class KfeMaintenanceCommandsTest {
    static final String STATUS = "kerosene.kfe-maintenance/v1";
    static final String ADMISSIONS = "kerosene.kfe-maintenance-admissions/v1";
    static final String PATH = "/api/admin/kfe/maintenance/status";
    KfeDiagnosticClient.Settings settings(String origin) {
        return new KfeDiagnosticClient.Settings(origin, "local", true, 2, "synthetic-request", "json", "synthetic-kfe-token");
    }

    @Test void hierarchyIsReadOnlyAndPaginationIsBoundedBeforeTransport() {
        var cli = new CommandLine(new KeroseneJavaCli());
        var commands = cli.getSubcommands().get("kfe").getSubcommands().get("maintenance").getSubcommands();
        assertEquals(java.util.Set.of("status", "admissions"), commands.keySet());
        assertFalse(commands.containsKey("clear")); assertFalse(commands.containsKey("resume"));
        for (String value : List.of("0", "101", "-1", "2147483647")) {
            var command = new KfeMaintenanceCommands.Admissions();
            new CommandLine(command).parseArgs("--limit", value);
            assertThrows(IllegalArgumentException.class, command::path);
        }
        var command = new KfeMaintenanceCommands.Admissions();
        new CommandLine(command).parseArgs();
        assertEquals("/api/admin/kfe/maintenance/admissions?limit=50", command.path());
        new CommandLine(command).parseArgs("--limit", "100", "--cursor", "abc_-123");
        assertEquals("/api/admin/kfe/maintenance/admissions?limit=100&cursor=abc_-123", command.path());
        for (String cursor : List.of("", "../x", "abc&clear=true", "secret=abc", "x".repeat(129))) {
            new CommandLine(command).parseArgs("--cursor", cursor);
            assertThrows(IllegalArgumentException.class, command::path);
        }
    }

    @Test void coreProfilesAndOriginsNeverSelectKfeOrForwardCoreCredentials() {
        for (String option : List.of("--endpoint", "--profile")) {
            var cli = new CommandLine(new KeroseneJavaCli());
            cli.parseArgs(option, "core-only", "--kfe-endpoint", "https://kfe.invalid", "kfe", "maintenance", "status");
            var command = (KfeMaintenanceCommands.Status) cli.getSubcommands().get("kfe")
                    .getSubcommands().get("maintenance").getSubcommands().get("status").getCommand();
            assertThrows(IllegalArgumentException.class, command::call);
        }
        assertFalse(settings("https://kfe.invalid").toString().contains("synthetic-kfe-token"));
    }

    @Test void rejectsForeignOriginsAndWrongMethodsRoutesOrSchemasBeforeNetwork() {
        for (String origin : List.of("https://user:secret@kfe.invalid", "https://kfe.invalid/path",
                "https://kfe.invalid?token=secret", "https://kfe.invalid#secret", "file:///tmp/x",
                "http://foreign.invalid", "invalid URI secret", "")) {
            assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(settings(origin), PATH, STATUS));
        }
        for (String path : List.of("/api/admin/kfe/maintenance/resume", PATH + "/", PATH + "?token=x",
                "/api/admin/kfe/maintenance/admissions?limit=0", "/api/admin/kfe/maintenance/admissions?limit=101",
                "/api/admin/kfe/maintenance/admissions?limit=50&cursor=abc&clear=1")) {
            assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(settings("https://kfe.invalid"), path, STATUS));
        }
        assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(settings("https://kfe.invalid"), PATH, ADMISSIONS));
    }

    @Test void productionAndStagingRequireDedicatedKfeCredentialAndHttpNeverProduction() {
        for (String environment : List.of("production", "staging")) {
            var noToken = new KfeDiagnosticClient.Settings("https://kfe.invalid", environment, true, 2, "id", "json", null);
            assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(noToken, PATH, STATUS));
            var plain = new KfeDiagnosticClient.Settings("http://127.0.0.1", environment, true, 2, "id", "json", "synthetic");
            assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(plain, PATH, STATUS));
        }
        var typo = new KfeDiagnosticClient.Settings("https://kfe.invalid", "prodution", false, 2, "id", "json", "synthetic");
        assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(typo, PATH, STATUS));
        var noLocal = new KfeDiagnosticClient.Settings("http://localhost", "local", false, 2, "id", "json", "synthetic");
        assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(noLocal, PATH, STATUS));
    }

    @Test void actualHttpGetUsesExactPathAndDedicatedTokenWithoutChangingState() throws Exception {
        AtomicReference<String> path = new AtomicReference<>(), token = new AtomicReference<>(), method = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString()); token.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            byte[] response = ("{\"schema\":\"" + ADMISSIONS + "\",\"diagnosticOnly\":true,\"entries\":[],\"nextCursor\":null}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            assertEquals(0, KfeDiagnosticClient.get(settings("http://127.0.0.1:" + server.getAddress().getPort()),
                    "/api/admin/kfe/maintenance/admissions?limit=50&cursor=abc_-123", ADMISSIONS));
            assertEquals("/api/admin/kfe/maintenance/admissions?limit=50&cursor=abc_-123", path.get());
            assertEquals("GET", method.get()); assertEquals("Bearer synthetic-kfe-token", token.get());
        } finally { server.stop(0); }
    }

    @Test void redirectsAndUnauthorizedResponsesDoNotFollowOrPrintBody() throws Exception {
        AtomicInteger count = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            count.incrementAndGet(); exchange.getResponseHeaders().set("Location", "/credential-target");
            byte[] bytes = "secret-synthetic-denial".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            assertEquals(4, KfeDiagnosticClient.get(settings("http://127.0.0.1:" + server.getAddress().getPort()), PATH, STATUS));
            assertEquals(1, count.get());
        } finally { server.stop(0); }
    }

    @Test void malformedDuplicateTrailingAndOversizedBodiesNeverBecomeSuccess() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = body.get(); exchange.sendResponseHeaders(200, response.length);
            try { exchange.getResponseBody().write(response); } catch (java.io.IOException cancelled) { }
            finally { exchange.close(); }
        }); server.start();
        try {
            for (String value : List.of("secret-synthetic-malformed", "{\"schema\":\"wrong\"}",
                    "{\"schema\":\"" + STATUS + "\",\"schema\":\"" + STATUS + "\"}",
                    "{\"schema\":\"" + STATUS + "\"} {}", "x".repeat(KfeDiagnosticClient.MAX_RESPONSE_BYTES + 1))) {
                body.set(value.getBytes(StandardCharsets.UTF_8));
                var failure = assertThrows(Exception.class, () -> KfeDiagnosticClient.get(
                        settings("http://127.0.0.1:" + server.getAddress().getPort()), PATH, STATUS));
                assertFalse(failure.getMessage().contains("secret-synthetic"));
                assertNull(failure.getCause());
            }
        } finally { server.stop(0); }
    }

    @Test void admissionPagesMustBeDiagnosticOnlyAndBounded() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String value : List.of("{\"schema\":\"" + ADMISSIONS + "\",\"entries\":[]}",
                "{\"schema\":\"" + ADMISSIONS + "\",\"diagnosticOnly\":false,\"entries\":[]}",
                "{\"schema\":\"" + ADMISSIONS + "\",\"diagnosticOnly\":\"true\",\"entries\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validateResponse(mapper.readTree(value), ADMISSIONS));
        }
        var oversized = mapper.createObjectNode().put("schema", ADMISSIONS).put("diagnosticOnly", true);
        var entries = oversized.putArray("entries"); for (int i = 0; i < 101; i++) { entries.addObject(); }
        assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validateResponse(oversized, ADMISSIONS));
    }

    @Test void completeBodyDeadlineCancelsAStalledResponse() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try {
                exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                release.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try {
            var request = new KfeDiagnosticClient.Settings("http://127.0.0.1:" + server.getAddress().getPort(),
                    "local", true, 1, "id", "json", "synthetic");
            long start = System.nanoTime();
            var failure = assertThrows(IllegalStateException.class, () -> KfeDiagnosticClient.get(request, PATH, STATUS));
            assertEquals("KFE diagnostic request unavailable.", failure.getMessage());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis() < 2500);
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void invalidTimeoutIdentityTokenAndOutputFailBeforeNetwork() {
        for (long timeout : List.of(0L, 121L)) {
            var request = new KfeDiagnosticClient.Settings("https://kfe.invalid", "staging", false,
                    timeout, "id", "json", "synthetic");
            assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(request, PATH, STATUS));
        }
        for (String token : List.of("synthetic\nsecret", "space token", "x".repeat(8193))) {
            var request = new KfeDiagnosticClient.Settings("https://kfe.invalid", "staging", false, 2, "id", "json", token);
            var failure = assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(request, PATH, STATUS));
            assertEquals("Invalid KFE administrative credential format.", failure.getMessage());
        }
        var request = new KfeDiagnosticClient.Settings("https://kfe.invalid", "staging", false, 2, "id", "invalid", "synthetic");
        assertThrows(IllegalArgumentException.class, () -> KfeDiagnosticClient.validate(request, PATH, STATUS));
    }

    @Test void actualMainProcessSelectsOnlyDedicatedTokenFromEnvironment() throws Exception {
        var tokens = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            tokens.add(token == null ? "absent" : token);
            byte[] response = ("{\"schema\":\"" + STATUS + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            String classpath = List.of(KeroseneJavaCli.class, CommandLine.class, ObjectMapper.class,
                    com.fasterxml.jackson.core.JsonParser.class, com.fasterxml.jackson.annotation.JsonProperty.class)
                    .stream().map(type -> {
                        try { return java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                        catch (Exception invalid) { throw new IllegalStateException(invalid); }
                    }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            for (boolean dedicated : List.of(false, true)) {
                var builder = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", classpath, Main.class.getName(), "--kfe-endpoint", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--allow-http-local", "--timeout", "3", "--output", "json", "kfe", "maintenance", "status");
                builder.environment().put("KEROSENE_ENVIRONMENT", "local");
                builder.environment().put("KEROSENE_ADMIN_TOKEN", "synthetic-core-token-must-not-forward");
                builder.environment().remove("JAVA_TOOL_OPTIONS");
                builder.environment().remove("JDK_JAVA_OPTIONS");
                builder.environment().remove("KEROSENE_KFE_ADMIN_TOKEN");
                if (dedicated) { builder.environment().put("KEROSENE_KFE_ADMIN_TOKEN", "synthetic-dedicated-token"); }
                Process process = builder.start();
                try {
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(0, process.exitValue(), err);
                    assertTrue(out.contains(STATUS));
                    assertFalse((out + err).contains("synthetic-core-token"));
                    assertFalse((out + err).contains("synthetic-dedicated-token"));
                } finally { if (process.isAlive()) { process.destroyForcibly(); } }
            }
            assertEquals(List.of("absent", "Bearer synthetic-dedicated-token"), tokens);
        } finally { server.stop(0); }
    }
}
