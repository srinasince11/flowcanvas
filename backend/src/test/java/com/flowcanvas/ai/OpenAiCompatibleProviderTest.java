package com.flowcanvas.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcanvas.config.AiProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Exercises the real HTTP path against a local server: no mocks of the HTTP client, so
 * timeouts, status handling and retries are tested exactly as they behave in production.
 */
class OpenAiCompatibleProviderTest {

    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"hello from the model"}}]}
            """;

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile Responder responder;

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, int call) throws IOException;
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int call = calls.incrementAndGet();
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder.respond(exchange, call);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiCompatibleProvider provider(int timeoutSeconds, int maxRetries) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new OpenAiCompatibleProvider(
                new AiProperties("openai", baseUrl, "test-key", "test-model", timeoutSeconds, maxRetries),
                RestClient.builder());
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static AiRequest request() {
        return new AiRequest("test-model", "Be brief.", "Say hello", 0.2);
    }

    @Test
    @DisplayName("sends the key, the system prompt and the user prompt, and returns the content")
    void happyPath() {
        responder = (exchange, call) -> reply(exchange, 200, OK_BODY);

        AiResponse response = provider(5, 0).complete(request());

        assertThat(response.content()).isEqualTo("hello from the model");
        assertThat(authHeaders).containsExactly("Bearer test-key");
        assertThat(bodies.get(0)).contains("\"role\":\"system\"").contains("Be brief.")
                .contains("\"role\":\"user\"").contains("Say hello");
    }

    @Test
    @DisplayName("retries a 503 and succeeds on the next attempt")
    void retriesServerErrors() {
        responder = (exchange, call) -> {
            if (call == 1) {
                reply(exchange, 503, "{\"error\":\"overloaded\"}");
            } else {
                reply(exchange, 200, OK_BODY);
            }
        };

        AiResponse response = provider(5, 2).complete(request());

        assertThat(response.content()).isEqualTo("hello from the model");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("gives up after the configured number of retries")
    void stopsAfterMaxRetries() {
        responder = (exchange, call) -> reply(exchange, 429, "{\"error\":\"rate limited\"}");

        assertThatThrownBy(() -> provider(5, 1).complete(request()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageContaining("after 2 attempts");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("does not retry a client error such as a rejected key")
    void doesNotRetryClientErrors() {
        responder = (exchange, call) -> reply(exchange, 401, "{\"error\":\"invalid key\"}");

        assertThatThrownBy(() -> provider(5, 3).complete(request()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("HTTP 401");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("enforces the configured timeout")
    void enforcesTimeout() {
        responder = (exchange, call) -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            reply(exchange, 200, OK_BODY);
        };

        long start = System.nanoTime();
        assertThatThrownBy(() -> provider(1, 0).complete(request()))
                .isInstanceOf(AiProviderException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs).isLessThan(2_500);
    }

    @Test
    @DisplayName("refuses to call out when no key is configured")
    void requiresKey() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        OpenAiCompatibleProvider keyless = new OpenAiCompatibleProvider(
                new AiProperties("openai", baseUrl, "", "test-model", 5, 0), RestClient.builder());

        assertThatThrownBy(() -> keyless.complete(request()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("AI_API_KEY");
        assertThat(calls.get()).isZero();
    }
}
