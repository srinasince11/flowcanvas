package com.flowcanvas.ai;

/**
 * A request to an AI provider.
 *
 * @param model       model identifier, e.g. {@code gpt-4o-mini}.
 * @param systemPrompt optional system instructions; may be {@code null}.
 * @param userPrompt  the user-facing prompt; never {@code null}.
 * @param temperature sampling temperature, clamped to [0, 2].
 */
public record AiRequest(
        String model,
        String systemPrompt,
        String userPrompt,
        double temperature
) {
    public AiRequest {
        if (userPrompt == null) {
            userPrompt = "";
        }
        temperature = Math.max(0.0, Math.min(2.0, temperature));
    }
}
