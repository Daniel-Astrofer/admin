package io.kerosene.jctl.application.port.out;

import java.net.URI;
import java.time.Duration;

/** Application port used by commands to access the audited admin API. */
public interface AdminApiClient {
    /** Executes one authenticated, read-only request through the service API.
     * @param request validated endpoint, path, timeout and audit context
     * @return HTTP status, response body and elapsed time
     * @throws Exception when transport or TLS setup fails
     */
    Response get(Request request) throws Exception;

    /** Immutable inputs needed to make one admin API request.
     * @param baseEndpoint service base URI selected by CLI configuration
     * @param path service-owned relative resource path
     * @param requestId correlation value propagated to the service
     * @param timeout maximum time allowed for connection and request
     * @param production whether production mutual TLS must be enabled
     */
    record Request(URI baseEndpoint, String path, String requestId, Duration timeout, boolean production) {
    }

    /** Captures the HTTP result and elapsed wall-clock duration returned to the CLI.
     * @param statusCode HTTP status code returned by the service
     * @param body response payload as received from the HTTP adapter
     * @param elapsedNanos elapsed monotonic time measured by the adapter
     */
    record Response(int statusCode, String body, long elapsedNanos) {}
}
