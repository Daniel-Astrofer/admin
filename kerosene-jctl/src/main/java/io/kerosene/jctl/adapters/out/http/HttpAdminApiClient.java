package io.kerosene.jctl.adapters.out.http;

import io.kerosene.jctl.adapters.out.config.TlsContextFactory;
import io.kerosene.jctl.application.port.out.AdminApiClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/** HTTP adapter for the authenticated, audited service-owned admin API. */
public final class HttpAdminApiClient implements AdminApiClient {

    /** Creates the stateless HTTP adapter. */
    public HttpAdminApiClient() {}

    /** Sends the authenticated GET and reports status, body, and elapsed nanoseconds.
     * @param request endpoint and request metadata supplied by the CLI
     * @return HTTP response and measured duration
     * @throws Exception on TLS, connection, timeout, interruption, or response-read failure
     */
    @Override
    public Response get(Request request) throws Exception {
        long startNanos = System.nanoTime();
        URI uri = URI.create(request.baseEndpoint() + request.path());
        HttpRequest.Builder httpRequest = HttpRequest.newBuilder(uri)
                .timeout(request.timeout())
                .header("Accept", "application/json")
                .header("X-Request-Id", request.requestId());
        Optional.ofNullable(System.getenv("KEROSENE_ADMIN_TOKEN"))
                .filter(value -> !value.isBlank())
                .ifPresent(value -> httpRequest.header("Authorization", "Bearer " + value));
        HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(request.timeout());
        if (request.production()) {
            client.sslContext(TlsContextFactory.productionContext());
        }
        HttpResponse<String> response = client.build()
                .send(httpRequest.GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), System.nanoTime() - startNanos);
    }
}
