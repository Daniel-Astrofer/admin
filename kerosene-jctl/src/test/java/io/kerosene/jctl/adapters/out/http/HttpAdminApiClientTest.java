package io.kerosene.jctl.adapters.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.kerosene.jctl.application.port.out.AdminApiClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpAdminApiClientTest {

    @Test
    void sendsRequestThroughTheServiceBoundary() throws Exception {
        AtomicReference<String> requestId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/admin/ledger/accounts/account-1", exchange -> {
                requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
                byte[] body = "{\"account\":\"account-1\"}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();

            AdminApiClient.Response response = new HttpAdminApiClient().get(new AdminApiClient.Request(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "/api/admin/ledger/accounts/account-1",
                    "jctl-test-request",
                    Duration.ofSeconds(2),
                    false));

            assertEquals(200, response.statusCode());
            assertEquals("{\"account\":\"account-1\"}", response.body());
            assertEquals("jctl-test-request", requestId.get());
            assertTrue(response.elapsedNanos() >= 0);
        } finally {
            server.stop(0);
        }
    }
}
