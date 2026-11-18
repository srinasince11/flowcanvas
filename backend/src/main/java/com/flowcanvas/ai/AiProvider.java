package com.flowcanvas.ai;

/**
 * The seam that lets FlowCanvas swap AI backends without touching the engine.
 *
 * <p>Two implementations ship with the project:
 * <ul>
 *   <li>{@link MockAiProvider} — deterministic, offline, zero credentials. The default.</li>
 *   <li>{@link OpenAiCompatibleProvider} — any endpoint speaking the OpenAI
 *       {@code /chat/completions} shape, configured purely through environment variables.</li>
 * </ul>
 *
 * <p>The engine only ever sees this interface, so a portfolio reviewer can swap in Anthropic,
 * Gemini or a local Ollama instance by writing one class.
 */
public interface AiProvider {

    /** Short provider identifier surfaced in the UI and the API responses. */
    String name();

    /** Whether this provider performs real network calls. */
    default boolean isRemote() {
        return false;
    }

    /**
     * Generates a completion.
     *
     * @throws AiProviderException when the provider fails; the engine turns this into a failed
     *                             node execution rather than a 500.
     */
    AiResponse complete(AiRequest request);
}
