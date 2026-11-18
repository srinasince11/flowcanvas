package com.flowcanvas.ai;

/**
 * The response from an AI provider.
 *
 * @param content the generated text.
 * @param model   the model that produced it.
 * @param provider the provider name, e.g. {@code mock} or {@code openai}.
 * @param latencyMs round-trip time measured by the provider call.
 */
public record AiResponse(String content, String model, String provider, long latencyMs) {
}
