package io.kerosene.jctl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "kerosene-jctl",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "Kerosene administrative client",
        subcommands = {
            KeroseneJavaCli.Ledger.class,
            KeroseneJavaCli.P2p.class,
            KeroseneJavaCli.Onramp.class,
            KeroseneJavaCli.Reconciliation.class,
            KeroseneJavaCli.Provider.class,
            CellCommands.class
        })
public final class KeroseneJavaCli implements Runnable {
    @Option(names = "--endpoint", description = "Core Admin API base URL")
    String endpoint;

    @Option(names = "--output", defaultValue = "text", description = "text, json or json-pretty")
    String output;

    @Option(names = "--timeout", defaultValue = "10", description = "Request timeout in seconds")
    long timeout;

    @Option(names = "--request-id", description = "Caller-provided request ID")
    String requestId;

    @Option(names = "--profile", description = "Profile in ~/.config/kerosene/profiles.toml")
    String profile;

    @Option(
            names = "--allow-http-local",
            description = "Allow HTTP only for localhost in a non-production environment")
    boolean allowHttpLocal;

    @Option(names = "--verbose")
    boolean verbose;

    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    public int get(String path) throws Exception {
        try {
            return request("GET", path, null);
        } catch (Exception e) {
            if (verbose) {
                System.err.printf("[AUDIT] invalid_input error=%s%n", e.getMessage());
            }
            throw e;
        }
    }

    public int post(String path, JsonNode body) throws Exception { return request("POST", path, body); }

    private int request(String method, String path, JsonNode bodyToSend) throws Exception {
        ProfileLoader.Profile loaded = profile == null ? null : ProfileLoader.load(profile);
        String baseEndpoint = endpoint != null ? endpoint : loaded == null ? null : loaded.endpoint();
        if (baseEndpoint == null || baseEndpoint.isBlank()) {
            throw new IllegalArgumentException("--endpoint or --profile is required");
        }
        String environment = loaded == null
                ? Optional.ofNullable(System.getenv("KEROSENE_ENVIRONMENT")).orElse("production")
                : loaded.environment();
        URI base = URI.create(baseEndpoint.replaceAll("/+$", ""));
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))) throw new IllegalArgumentException("Core HTTPS origin required");
        if (path == null || !path.startsWith("/api/admin/") || path.startsWith("//")) throw new IllegalArgumentException("Only Core Admin API routes are allowed");
        if (timeout < 1 || timeout > 120) throw new IllegalArgumentException("Timeout must be 1..120 seconds");
        OutputFormatter.requireSupportedMode(output);
        boolean localHttp = "http".equalsIgnoreCase(base.getScheme())
                && ("localhost".equalsIgnoreCase(base.getHost())
                        || "127.0.0.1".equals(base.getHost()))
                && !"production".equalsIgnoreCase(environment)
                && allowHttpLocal;
        if (!"https".equalsIgnoreCase(base.getScheme()) && !localHttp) {
            throw new IllegalArgumentException(
                    "Admin API requires HTTPS; local HTTP needs --allow-http-local and a non-production environment");
        }
        Optional<String> token = Optional.ofNullable(System.getenv("KEROSENE_ADMIN_TOKEN"))
                .filter(value -> !value.isBlank());
        if ("production".equalsIgnoreCase(environment)) {
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Production requires a short-lived KEROSENE_ADMIN_TOKEN");
            }
            ProfileLoader.requirePrivateRegularFile("javax.net.ssl.keyStore");
            ProfileLoader.requirePrivateRegularFile("javax.net.ssl.trustStore");
        }
        URI uri = URI.create(base + path);
        String reqId = requestId();
        long startNanos = System.nanoTime();

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeout))
                .header("Accept", "application/json")
                .header("X-Request-Id", reqId);
        if (verbose) {
            System.err.printf("[VERBOSE] %s %s requestId=%s%n", method, uri, reqId);
        }
        token.ifPresent(value -> request.header("Authorization", "Bearer " + value));
        HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout)).followRedirects(HttpClient.Redirect.NEVER);
        if ("production".equalsIgnoreCase(environment)) {
            client.sslContext(TlsContextFactory.productionContext());
        }
        if (bodyToSend != null) request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(bodyToSend)));
        else request.GET();
        HttpResponse<String> response = client.build().send(request.build(), HttpResponse.BodyHandlers.ofString());
        long elapsedNanos = System.nanoTime() - startNanos;
        if (response.statusCode() / 100 != 2) {
            System.err.printf(
                    "Admin API rejected request: status=%d requestId=%s%n",
                    response.statusCode(), reqId);
            if (verbose) {
                System.err.printf("[VERBOSE] status=%d requestId=%s elapsed=%dms%n",
                        response.statusCode(), reqId, elapsedNanos / 1_000_000);
                System.err.printf("[AUDIT] denial requestId=%s path=%s status=%d%n",
                        reqId, path, response.statusCode());
            }
            return 4;
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(response.body());
        System.out.println(formatOutput(body, output));
        if (verbose) {
            System.err.printf("[VERBOSE] status=%d requestId=%s elapsed=%dms%n",
                    response.statusCode(), reqId, elapsedNanos / 1_000_000);
            System.err.printf("[AUDIT] success requestId=%s path=%s status=%d%n",
                    reqId, path, response.statusCode());
        }
        return 0;
    }

    static String formatOutput(JsonNode body, String outputMode) throws Exception {
        return OutputFormatter.format(body, outputMode);
    }

    // package-private for testing
    String requestId() {
        if (requestId != null && !requestId.isBlank() && !requestId.matches("[a-zA-Z0-9._-]{1,128}")) throw new IllegalArgumentException("Invalid request ID");
        return requestId == null || requestId.isBlank()
                ? "jctl-" + UUID.randomUUID()
                : requestId;
    }

    static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Command
    abstract static class ReadCommand implements Callable<Integer> {
        @Spec CommandSpec spec;
        abstract String path();
        @Override
        public Integer call() throws Exception {
            return ((KeroseneJavaCli) spec.root().userObject()).get(path());
        }
    }

    @Command(name = "ledger", subcommands = {Ledger.Account.class, Ledger.Journal.class})
    static final class Ledger implements Runnable {
        @Override public void run() {}

        @Command(name = "account", subcommands = Account.Inspect.class)
        static final class Account implements Runnable {
            @Override public void run() {}
            @Command(name = "inspect")
            static final class Inspect extends ReadCommand {
                @Parameters(index = "0") String id;
                @Override String path() { return "/api/admin/ledger/accounts/" + segment(id); }
            }
        }

        @Command(name = "journal", subcommands = Journal.Inspect.class)
        static final class Journal implements Runnable {
            @Override public void run() {}
            @Command(name = "inspect")
            static final class Inspect extends ReadCommand {
                @Parameters(index = "0") String id;
                @Override String path() { return "/api/admin/ledger/journals/" + segment(id); }
            }
        }
    }

    @Command(name = "p2p", subcommands = P2p.Order.class)
    static final class P2p implements Runnable {
        @Override public void run() {}
        @Command(name = "order", subcommands = Order.Inspect.class)
        static final class Order implements Runnable {
            @Override public void run() {}
            @Command(name = "inspect")
            static final class Inspect extends ReadCommand {
                @Parameters(index = "0") String id;
                @Override String path() { return "/api/admin/p2p/orders/" + segment(id); }
            }
        }
    }

    @Command(name = "onramp", subcommands = Onramp.Order.class)
    static final class Onramp implements Runnable {
        @Override public void run() {}
        @Command(name = "order", subcommands = Order.Inspect.class)
        static final class Order implements Runnable {
            @Override public void run() {}
            @Command(name = "inspect")
            static final class Inspect extends ReadCommand {
                @Parameters(index = "0") String id;
                @Override String path() { return "/api/admin/onramp/orders/" + segment(id); }
            }
        }
    }

    @Command(name = "reconciliation", subcommands = Reconciliation.Status.class)
    static final class Reconciliation implements Runnable {
        @Override public void run() {}
        @Command(name = "status")
        static final class Status extends ReadCommand {
            @Override String path() { return "/api/admin/reconciliation/status"; }
        }
    }

    @Command(name = "provider", subcommands = Provider.Connection.class)
    static final class Provider implements Runnable {
        @Override public void run() {}
        @Command(name = "connection", subcommands = Connection.Validate.class)
        static final class Connection implements Runnable {
            @Override public void run() {}
            @Command(name = "validate")
            static final class Validate extends ReadCommand {
                @Parameters(index = "0") String id;
                @Override String path() {
                    return "/api/admin/providers/connections/" + segment(id) + "/validation";
                }
            }
        }
    }
}
