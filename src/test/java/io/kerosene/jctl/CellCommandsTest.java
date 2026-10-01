package io.kerosene.jctl;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class CellCommandsTest {
    @Test void routesCoverEveryCellEvidenceSectionAndPlanHistory() {
        var cli = new CommandLine(new KeroseneJavaCli());
        for (String name : new String[]{"status", "releases", "quorum", "blockers", "backups", "update", "package"}) assertTrue(cli.getSubcommands().get("cell").getSubcommands().containsKey(name));
        var update = cli.getSubcommands().get("cell").getSubcommands().get("update");
        for (String name : new String[]{"status", "plan", "plans", "inspect"}) assertTrue(update.getSubcommands().containsKey(name));
        assertFalse(update.getSubcommands().containsKey("deploy"));
    }
    @Test void rejectsUnapprovedLocalHttpBeforeNetwork() throws Exception {
        var path = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString());
            exchange.getResponseHeaders().set("Location", "/must-not-follow"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            var cli = new KeroseneJavaCli();
            // A profile sets local environment without changing process-global environment variables.
            cli.endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            cli.timeout = 2; cli.output = "json"; cli.allowHttpLocal = true;
            // HTTPS origin requirement is separately enforced for default production.
            assertThrows(IllegalArgumentException.class, () -> cli.get(CellCommands.BASE));
            assertNull(path.get());
        } finally { server.stop(0); }
    }
    @Test void rejectsOriginCredentialsAndInvalidOutputBeforeRequest() {
        var cli = new KeroseneJavaCli(); cli.endpoint = "https://user:secret@example.invalid"; cli.timeout = 10; cli.output = "json";
        assertThrows(IllegalArgumentException.class, () -> cli.get(CellCommands.BASE));
        cli.endpoint = "https://example.invalid"; cli.output = "bad";
        assertThrows(IllegalArgumentException.class, () -> cli.get(CellCommands.BASE));
    }
}
