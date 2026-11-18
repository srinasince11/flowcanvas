package com.flowcanvas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised AI provider configuration.
 *
 * <p>Every value is bound from an environment variable (see {@code .env.example}); nothing is
 * hardcoded, and the API key is never written to logs.
 *
 * @param provider        {@code mock} (default, offline) or {@code openai}.
 * @param baseUrl         base URL of an OpenAI-compatible API.
 * @param apiKey          bearer token; blank in mock mode.
 * @param model           default model identifier.
 * @param timeoutSeconds  per-request timeout.
 * @param maxRetries      how many times a transient provider failure is retried.
 */
@ConfigurationProperties(prefix = "flowcanvas.ai")
public record AiProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        int maxRetries
) {
    public AiProperties {
        provider = provider == null || provider.isBlank() ? "mock" : provider.trim().toLowerCase();
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.trim();
        model = model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        maxRetries = Math.max(0, maxRetries);
    }

    public boolean isRemote() {
        return "openai".equals(provider);
    }
}
