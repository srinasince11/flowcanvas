package com.flowcanvas.ai;

import com.flowcanvas.config.AiProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * A real provider speaking the OpenAI {@code /chat/completions} protocol.
 *
 * <p>Credentials come exclusively from the environment ({@code AI_API_KEY},
 * {@code AI_BASE_URL}, {@code AI_MODEL}) — there is no secret, key or default token anywhere
 * in this repository, and the key is never logged.
 *
 * <p>Any vendor exposing the OpenAI shape (OpenAI, Azure OpenAI, Together, Groq, Ollama,
 * LM Studio, vLLM) can be pointed at by changing the base URL alone.
 *
 * <p><b>Resilience.</b> Every request is bounded by {@code AI_TIMEOUT_SECONDS}. Failures that
 * are worth retrying — rate limiting ({@code 429}), server errors ({@code 5xx}) and network
 * errors or timeouts — are retried up to {@code AI_MAX_RETRIES} times with exponential
 * backoff, honouring a {@code Retry-After} header when the server sends one. Client errors
 * such as a bad key ({@code 401}) or an unknown model ({@code 404}) fail immediately:
 * retrying them only burns time and quota.
 */
@Component
@ConditionalOnProperty(name = "flowcanvas.ai.provider", havingValue = "openai")
public class OpenAiCompatibleProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    static final long BASE_BACKOFF_MS = 250;
    static final long MAX_BACKOFF_MS = 10_000;

    private final AiProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleProvider(AiProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    @Override
    public AiResponse complete(AiRequest request) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new AiProviderException(
                    "AI_API_KEY is not configured. Set it together with AI_PROVIDER=openai, "
                            + "or use the default mock provider.");
        }

        long start = System.nanoTime();
        int attempts = properties.maxRetries() + 1;
        for (int attempt = 1; ; attempt++) {
            try {
                String content = send(request);
                long latencyMs = (System.nanoTime() - start) / 1_000_000L;
                return new AiResponse(content, request.model(), name(), latencyMs);
            } catch (RestClientException exception) {
                if (!isRetryable(exception) || attempt >= attempts) {
                    throw new AiProviderException(describe(exception, attempt), exception);
                }
                long backoff = backoffMs(exception, attempt);
                log.warn("AI call for model {} failed (attempt {}/{}): {} — retrying in {} ms",
                        request.model(), attempt, attempts, summary(exception), backoff);
                sleep(backoff);
            }
        }
    }

    private String send(AiRequest request) {
        Map<String, Object> body = Map.of(
                "model", request.model(),
                "temperature", request.temperature(),
                "messages", buildMessages(request));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .body(body)
                .retrieve()
                .body(Map.class);
        return extractContent(response);
    }

    static boolean isRetryable(RestClientException exception) {
        if (exception instanceof ResourceAccessException) {
            return true; // connection refused, reset, or timed out
        }
        if (exception instanceof RestClientResponseException response) {
            HttpStatusCode status = response.getStatusCode();
            return status.value() == 429 || status.is5xxServerError();
        }
        return false;
    }

    static long backoffMs(RestClientException exception, int attempt) {
        if (exception instanceof RestClientResponseException response
                && response.getResponseHeaders() != null) {
            String retryAfter = response.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                try {
                    return Math.min(MAX_BACKOFF_MS, Long.parseLong(retryAfter.trim()) * 1000L);
                } catch (NumberFormatException ignored) {
                    // An HTTP-date Retry-After: fall back to exponential backoff.
                }
            }
        }
        return Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << (attempt - 1));
    }

    private static String describe(RestClientException exception, int attempts) {
        String suffix = attempts > 1 ? " (after " + attempts + " attempts)" : "";
        return "AI provider call failed: " + summary(exception) + suffix;
    }

    /** A short, log-safe description: the status and a truncated response body. */
    private static String summary(RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            String body = response.getResponseBodyAsString();
            body = body.length() > 300 ? body.substring(0, 300) + "…" : body;
            return "HTTP " + response.getStatusCode().value() + (body.isBlank() ? "" : " " + body);
        }
        return exception.getMessage();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("AI provider call was interrupted.", interrupted);
        }
    }

    private List<Map<String, String>> buildMessages(AiRequest request) {
        if (request.systemPrompt() == null || request.systemPrompt().isBlank()) {
            return List.of(Map.of("role", "user", "content", request.userPrompt()));
        }
        return List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt()));
    }

    private String extractContent(Map<String, Object> response) {
        if (response == null) {
            throw new AiProviderException("AI provider returned an empty response body.");
        }
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new AiProviderException("AI provider response did not contain any choices.");
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            throw new AiProviderException("AI provider returned a malformed choice entry.");
        }
        Object message = choice.get("message");
        if (message instanceof Map<?, ?> messageMap) {
            Object content = messageMap.get("content");
            if (content != null) {
                return String.valueOf(content);
            }
        }
        throw new AiProviderException("AI provider response did not contain message content.");
    }

    /** The configured per-request timeout. */
    public Duration timeout() {
        return Duration.ofSeconds(properties.timeoutSeconds());
    }
}
