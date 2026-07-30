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
import java.util.Optional;
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
    @Option(names = "--endpoint", required = true, description = "Core/KFE Admin API base URL")
    String endpoint;

    @Option(names = "--output", defaultValue = "text", description = "text, json or json-pretty")
    String output;

    @Option(names = "--timeout", defaultValue = "10", description = "Request timeout in seconds")
    long timeout;

    @Option(names = "--request-id", description = "Caller-provided request ID")
    String requestId;

    @Option(names = "--profile", description = "Reserved profile name; profiles never store tokens")
    String profile;

    @Option(names = "--verbose")
    boolean verbose;

    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    int get(String path) throws Exception {
        URI uri = URI.create(endpoint.replaceAll("/+$", "") + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeout))
                .header("Accept", "application/json")
                .header("X-Request-Id", requestId());
        Optional.ofNullable(System.getenv("KEROSENE_ADMIN_TOKEN"))
                .filter(token -> !token.isBlank())
                .ifPresent(token -> request.header("Authorization", "Bearer " + token));
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .build()
                .send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            System.err.printf(
                    "Admin API rejected request: status=%d requestId=%s%n",
                    response.statusCode(), requestId());
            return 4;
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(response.body());
        if ("json".equals(output)) {
            System.out.println(mapper.writeValueAsString(body));
        } else {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(body));
        }
        return 0;
    }

    private String requestId() {
        return requestId == null || requestId.isBlank()
                ? "jctl-" + ProcessHandle.current().pid()
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
