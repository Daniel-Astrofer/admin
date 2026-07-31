package io.kerosene.jctl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
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
        description = "Kerosene Core administrative client",
        subcommands = {
            KeroseneJavaCli.Ledger.class,
            KeroseneJavaCli.P2p.class,
            KeroseneJavaCli.Onramp.class,
            KeroseneJavaCli.Reconciliation.class,
            KeroseneJavaCli.Provider.class
        })
public final class KeroseneJavaCli implements Runnable {
    @Option(names = "--endpoint", description = "Core/KFE Admin API base URL")
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

    int get(String path) throws Exception {
        try {
            return doGet(path);
        } catch (Exception e) {
            if (verbose) {
                System.err.printf("[AUDIT] invalid_input error=%s%n", e.getMessage());
            }
            throw e;
        }
    }

    private int doGet(String path) throws Exception {
        ProfileLoader.Profile loaded = profile == null ? null : ProfileLoader.load(profile);
        String baseEndpoint = endpoint != null ? endpoint : loaded == null ? null : loaded.endpoint();
        if (baseEndpoint == null || baseEndpoint.isBlank()) {
            throw new IllegalArgumentException("--endpoint or --profile is required");
        }
        String environment = loaded == null
                ? Optional.ofNullable(System.getenv("KEROSENE_ENVIRONMENT")).orElse("production")
                : loaded.environment();
        URI base = URI.create(baseEndpoint.replaceAll("/+$", ""));
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
            System.err.printf("[VERBOSE] %s %s requestId=%s%n", "GET", uri, reqId);
        }
        token.ifPresent(value -> request.header("Authorization", "Bearer " + value));
        HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout));
        if ("production".equalsIgnoreCase(environment)) {
            client.sslContext(TlsContextFactory.productionContext());
        }
        HttpResponse<String> response = client.build()
                .send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
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
        ObjectMapper mapper = new ObjectMapper();
        if ("json".equals(outputMode)) {
            return mapper.writeValueAsString(body);
        } else if ("text".equals(outputMode)) {
            StringBuilder sb = new StringBuilder();
            formatText(body, sb, 0);
            return sb.toString().stripTrailing();
        } else {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            return mapper.writeValueAsString(body);
        }
    }

    private static void formatText(JsonNode node, StringBuilder sb, int depth) {
        String indent = "  ".repeat(depth);
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                sb.append(indent).append(key).append("=");
                if (value.isObject()) {
                    sb.append("{\n");
                    formatText(value, sb, depth + 1);
                    sb.append(indent).append("}");
                } else if (value.isArray()) {
                    sb.append("[").append(value.size()).append(" items]");
                } else if (value.isTextual()) {
                    sb.append(value.asText());
                } else if (value.isNull()) {
                    sb.append("null");
                } else {
                    sb.append(value.asText());
                }
                if (fields.hasNext()) {
                    sb.append("\n");
                }
            }
        } else if (node.isArray()) {
            sb.append("[").append(node.size()).append(" items]");
        } else if (node.isNull()) {
            sb.append("null");
        } else {
            sb.append(node.asText());
        }
    }

    // package-private for testing
    String requestId() {
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
