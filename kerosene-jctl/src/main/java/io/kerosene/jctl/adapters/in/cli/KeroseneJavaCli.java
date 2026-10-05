package io.kerosene.jctl.adapters.in.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kerosene.jctl.adapters.out.config.ProfileLoader;
import io.kerosene.jctl.adapters.out.http.HttpAdminApiClient;
import io.kerosene.jctl.application.port.out.AdminApiClient;
import java.net.URI;
import java.net.URLEncoder;
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

/** Read-only administrative CLI; all state access is delegated to the service API. */
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
            KeroseneJavaCli.Provider.class
        })
public final class KeroseneJavaCli implements Runnable {
    private final AdminApiClient adminApiClient;

    /** Creates the production CLI with the HTTP adapter. */
    public KeroseneJavaCli() {
        this(new HttpAdminApiClient());
    }

    /** Injects the API port; package visibility keeps substitution scoped to this adapter. */
    KeroseneJavaCli(AdminApiClient adminApiClient) {
        this.adminApiClient = adminApiClient;
    }

    /** Overrides the service API base URL resolved from a profile. */
    @Option(names = "--endpoint", description = "Core/KFE Admin API base URL")
    String endpoint;

    /** Selects text, compact JSON, or indented JSON response rendering. */
    @Option(names = "--output", defaultValue = "text", description = "text, json or json-pretty")
    String output;

    /** Sets the connect and request timeout in seconds. */
    @Option(names = "--timeout", defaultValue = "10", description = "Request timeout in seconds")
    long timeout;

    /** Supplies a stable request correlation ID instead of generating one. */
    @Option(names = "--request-id", description = "Caller-provided request ID")
    String requestId;

    /** Selects the named endpoint/environment profile. */
    @Option(names = "--profile", description = "Profile in ~/.config/kerosene/profiles.toml")
    String profile;

    /** Allows cleartext HTTP only for localhost outside production. */
    @Option(
            names = "--allow-http-local",
            description = "Allow HTTP only for localhost in a non-production environment")
    boolean allowHttpLocal;

    /** Enables request timing and audit outcome details on standard error. */
    @Option(names = "--verbose")
    boolean verbose;

    /** Prints available commands when no actionable subcommand was selected. */
    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    /** Fetches a service-owned admin resource and writes it in the selected format.
     * @param path relative admin resource path
     * @return process status code (zero on success, four when the API rejects the request)
     * @throws Exception when configuration, transport, or response parsing fails
     */
    public int get(String path) throws Exception {
        try {
            return doGet(path);
        } catch (Exception e) {
            if (verbose) {
                System.err.printf("[AUDIT] invalid_input error=%s%n", e.getMessage());
            }
            throw e;
        }
    }

    /** Validates endpoint, transport and production credentials before issuing the GET. */
    private int doGet(String path) throws Exception {
        ProfileLoader.Profile loaded = profile == null ? null : ProfileLoader.load(profile);
        String baseEndpoint = endpoint != null ? endpoint : loaded == null ? null : loaded.endpoint();
        if (baseEndpoint == null || baseEndpoint.isBlank()) {
            throw new IllegalArgumentException("--endpoint or --profile is required");
        }
        OutputFormatter.requireSupportedMode(output);
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
        boolean production = "production".equalsIgnoreCase(environment);
        if (production) {
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Production requires a short-lived KEROSENE_ADMIN_TOKEN");
            }
            ProfileLoader.requirePrivateRegularFile("javax.net.ssl.keyStore");
            ProfileLoader.requirePrivateRegularFile("javax.net.ssl.trustStore");
        }
        URI uri = URI.create(base + path);
        String reqId = requestId();
        if (verbose) {
            System.err.printf("[VERBOSE] %s %s requestId=%s%n", "GET", uri, reqId);
        }
        AdminApiClient.Response response = adminApiClient.get(new AdminApiClient.Request(
                base, path, reqId, Duration.ofSeconds(timeout), production));
        if (response.statusCode() / 100 != 2) {
            System.err.printf(
                    "Admin API rejected request: status=%d requestId=%s%n",
                    response.statusCode(), reqId);
            if (verbose) {
                System.err.printf("[VERBOSE] status=%d requestId=%s elapsed=%dms%n",
                        response.statusCode(), reqId, response.elapsedNanos() / 1_000_000);
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
                    response.statusCode(), reqId, response.elapsedNanos() / 1_000_000);
            System.err.printf("[AUDIT] success requestId=%s path=%s status=%d%n",
                    reqId, path, response.statusCode());
        }
        return 0;
    }

    /** Formats a parsed response through the shared output formatter. */
    static String formatOutput(JsonNode body, String outputMode) throws Exception {
        return OutputFormatter.format(body, outputMode);
    }

    /** Returns the caller's correlation ID or creates a unique CLI-scoped ID. */
    String requestId() {
        return requestId == null || requestId.isBlank()
                ? "jctl-" + UUID.randomUUID()
                : requestId;
    }

    /** Percent-encodes one path segment so identifiers cannot alter route structure.
     * @param value raw identifier value
     * @return UTF-8 form-encoded value with spaces represented as {@code %20}
     */
    static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Common execution path for leaf commands that perform a read-only request. */
    @Command
    /** Base class that dispatches a read-only leaf command through the root CLI. */
    abstract static class ReadCommand implements Callable<Integer> {
        @Spec
        CommandSpec spec;

        /** @return API path for the concrete inspection command. */
        abstract String path();

        /** Resolves the root CLI and executes this command's API request. */
        @Override
        public Integer call() throws Exception {
            return ((KeroseneJavaCli) spec.root().userObject()).get(path());
        }
    }

    /** Groups read-only ledger inspection commands. */
    @Command(name = "ledger", subcommands = {Ledger.Account.class, Ledger.Journal.class})
    static final class Ledger implements Runnable {
        @Override
        public void run() {}

        /** Groups account-specific ledger operations. */
        @Command(name = "account", subcommands = Account.Inspect.class)
        static final class Account implements Runnable {
            @Override
            public void run() {}

            /** Fetches one ledger account by its path-encoded identifier. */
            @Command(name = "inspect")
            static final class Inspect extends ReadCommand {
                /** Account identifier supplied as the first positional argument. */
                @Parameters(index = "0")
                String id;

                /** @return service route for inspecting the selected account. */
                @Override
                String path() {
                    return "/api/admin/ledger/accounts/" + segment(id);
                }
            }
        }

        /** Groups journal-specific ledger operations. */
        @Command(name = "journal", subcommands = Journal.Inspect.class)
        static final class Journal implements Runnable {
            @Override
            public void run() {}

            /** Fetches one ledger journal by its path-encoded identifier. */
            @Command(name = "inspect")
            static final class Inspect extends ReadCommand {
                /** Journal identifier supplied as the first positional argument. */
                @Parameters(index = "0")
                String id;

                /** @return service route for inspecting the selected journal. */
                @Override
                String path() {
                    return "/api/admin/ledger/journals/" + segment(id);
                }
            }
        }
    }

    /** Groups peer-to-peer order inspection commands. */
    @Command(name = "p2p", subcommands = P2p.Order.class)
    static final class P2p implements Runnable {
        @Override
        public void run() {}

        /** Groups peer-to-peer order operations. */
        @Command(name = "order", subcommands = Order.Inspect.class)
        static final class Order implements Runnable {
            @Override
            public void run() {}

            /** Fetches one peer-to-peer order by its identifier. */
            @Command(name = "inspect")
            static final class Inspect extends ReadCommand {
                /** Order identifier supplied as the first positional argument. */
                @Parameters(index = "0")
                String id;

                /** @return service route for inspecting the selected order. */
                @Override
                String path() {
                    return "/api/admin/p2p/orders/" + segment(id);
                }
            }
        }
    }

    /** Groups on-ramp order inspection commands. */
    @Command(name = "onramp", subcommands = Onramp.Order.class)
    static final class Onramp implements Runnable {
        @Override
        public void run() {}

        /** Groups on-ramp order operations. */
        @Command(name = "order", subcommands = Order.Inspect.class)
        static final class Order implements Runnable {
            @Override
            public void run() {}

            /** Fetches one on-ramp order by its identifier. */
            @Command(name = "inspect")
            static final class Inspect extends ReadCommand {
                /** Order identifier supplied as the first positional argument. */
                @Parameters(index = "0")
                String id;

                /** @return service route for inspecting the selected on-ramp order. */
                @Override
                String path() {
                    return "/api/admin/onramp/orders/" + segment(id);
                }
            }
        }
    }

    /** Groups reconciliation status inspection commands. */
    @Command(name = "reconciliation", subcommands = Reconciliation.Status.class)
    static final class Reconciliation implements Runnable {
        @Override
        public void run() {}

        /** Reads the current reconciliation status. */
        @Command(name = "status")
        static final class Status extends ReadCommand {
            /** @return service route for the reconciliation status resource. */
            @Override
            String path() {
                return "/api/admin/reconciliation/status";
            }
        }
    }

    /** Groups provider inspection and validation commands. */
    @Command(name = "provider", subcommands = Provider.Connection.class)
    static final class Provider implements Runnable {
        @Override
        public void run() {}

        /** Groups provider connection operations. */
        @Command(name = "connection", subcommands = Connection.Validate.class)
        static final class Connection implements Runnable {
            @Override
            public void run() {}

            /** Fetches the latest validation result for a provider connection. */
            @Command(name = "validate")
            static final class Validate extends ReadCommand {
                /** Provider connection identifier supplied as the first positional argument. */
                @Parameters(index = "0")
                String id;

                /** @return service route for the selected connection validation. */
                @Override
                String path() {
                    return "/api/admin/providers/connections/" + segment(id) + "/validation";
                }
            }
        }
    }
}
