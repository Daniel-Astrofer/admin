package io.kerosene.jctl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Diagnostic-only transport; never reuses Core credentials, follows redirects or writes. */
final class KfeDiagnosticClient {
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    record Settings(String endpoint, String environment, boolean allowHttpLocal, long timeout,
                    String requestId, String output, String token) {
        // Record's default toString would expose the bearer credential.
        @Override public String toString() { return "KFE diagnostic settings (credentials omitted)"; }
    }

    static int get(Settings settings, String path, String schema) throws Exception {
        URI origin = validate(settings, path, schema);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(settings.timeout()))
                .followRedirects(HttpClient.Redirect.NEVER);
        if ("production".equals(settings.environment())) {
            ProfileLoader.requirePrivateRegularFile("javax.net.ssl.keyStore");
            ProfileLoader.requirePrivateRegularFile("javax.net.ssl.trustStore");
            client.sslContext(TlsContextFactory.productionContext());
        }
        var request = HttpRequest.newBuilder(URI.create(origin + path)).GET()
                .timeout(Duration.ofSeconds(settings.timeout())).header("Accept", "application/json")
                .header("X-Request-Id", settings.requestId());
        if (settings.token() != null && !settings.token().isBlank()) {
            request.header("Authorization", "Bearer " + settings.token());
        }
        var pending = client.build().sendAsync(request.build(), info -> new LimitedBody());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(settings.timeout(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("KFE diagnostic request interrupted.");
        } catch (Exception unavailable) {
            pending.cancel(true);
            throw new IllegalStateException("KFE diagnostic request unavailable.");
        }
        if (response.statusCode() / 100 != 2) {
            System.err.printf("KFE Admin API rejected request: status=%d requestId=%s%n",
                    response.statusCode(), settings.requestId());
            return 4;
        }
        JsonNode body;
        try {
            ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            body = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response.body());
            validateResponse(body, schema);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid KFE diagnostic response.");
        }
        System.out.println(KeroseneJavaCli.formatOutput(body, settings.output()));
        return 0;
    }

    static URI validate(Settings settings, String path, String schema) {
        if (settings.endpoint() == null || settings.endpoint().isBlank()) {
            throw new IllegalArgumentException("--kfe-endpoint is required.");
        }
        URI origin;
        try { origin = URI.create(settings.endpoint()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid KFE HTTPS origin."); }
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) {
            throw new IllegalArgumentException("KFE endpoint must be an explicit HTTPS origin.");
        }
        if (!List.of("production", "staging", "local").contains(settings.environment())) {
            throw new IllegalArgumentException("Explicit production, staging or local environment required.");
        }
        boolean local = "local".equals(settings.environment()) && settings.allowHttpLocal()
                && "http".equalsIgnoreCase(origin.getScheme())
                && ("127.0.0.1".equals(origin.getHost()) || "localhost".equalsIgnoreCase(origin.getHost()));
        if (!"https".equalsIgnoreCase(origin.getScheme()) && !local) {
            throw new IllegalArgumentException("KFE requires HTTPS; HTTP is limited to explicit local diagnostics.");
        }
        if (!"local".equals(settings.environment()) && (settings.token() == null || settings.token().isBlank())) {
            throw new IllegalArgumentException("A short-lived KEROSENE_KFE_ADMIN_TOKEN is required.");
        }
        if (settings.token() != null && (settings.token().length() > 8192
                || settings.token().chars().anyMatch(c -> c < 33 || c > 126))) {
            throw new IllegalArgumentException("Invalid KFE administrative credential format.");
        }
        if (settings.timeout() < 1 || settings.timeout() > 120 || settings.requestId() == null
                || !settings.requestId().matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid diagnostic timeout or request ID.");
        }
        OutputFormatter.requireSupportedMode(settings.output());
        boolean status = path != null && path.equals("/api/admin/kfe/maintenance/status")
                && "kerosene.kfe-maintenance/v1".equals(schema);
        boolean admissions = path != null && path.matches("/api/admin/kfe/maintenance/admissions\\?limit=(?:[1-9][0-9]?|100)(?:&cursor=[A-Za-z0-9_-]{1,128})?")
                && "kerosene.kfe-maintenance-admissions/v1".equals(schema);
        if (!status && !admissions) { throw new IllegalArgumentException("Only exact KFE diagnostic reads are supported."); }
        return URI.create(settings.endpoint().replaceAll("/+$", ""));
    }

    static void validateResponse(JsonNode body, String schema) {
        if (body == null || !body.isObject() || !schema.equals(body.path("schema").asText())) {
            throw new IllegalArgumentException("Invalid diagnostic schema.");
        }
        if (schema.equals("kerosene.kfe-maintenance-admissions/v1")
                && (!body.path("diagnosticOnly").isBoolean() || !body.path("diagnosticOnly").booleanValue()
                || !body.path("entries").isArray() || body.path("entries").size() > 100)) {
            throw new IllegalArgumentException("Invalid diagnostic-only page.");
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalStateException("Diagnostic response exceeds size limit."));
                    return;
                }
                byte[] data = new byte[chunk.remaining()];
                chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { body.completeExceptionally(new IllegalStateException("Diagnostic body unavailable.")); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
