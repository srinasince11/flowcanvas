package com.flowcanvas.ai;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default provider: fully offline, deterministic and free.
 *
 * <p>It is not a stub that returns "TODO" — it produces plausible, deterministic output so the
 * whole product (canvas → validation → execution → results) is demonstrable with zero
 * credentials, which is what makes the project reviewable on a laptop with no API budget.
 *
 * <p>Determinism comes from hashing the prompt: the same prompt always yields the same text,
 * so golden-file tests are stable and the UI never flickers between runs.
 *
 * <p>It also simulates failures on demand: a prompt containing {@code [[fail]]} raises an
 * error, and a prompt containing {@code [[slow]]} sleeps. This gives the UI a genuine error
 * path to render instead of a hard-coded one.
 */
@Component
@ConditionalOnProperty(name = "flowcanvas.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiProvider implements AiProvider {

    private static final String FAIL_MARKER = "[[fail]]";
    private static final String SLOW_MARKER = "[[slow]]";

    private final AtomicLong callCount = new AtomicLong();
    private final long latencyMs;

    /**
     * @param latencyMs simulated per-call latency. A real model takes seconds; a little
     *                  latency in the demo lets the live per-node progress actually be seen.
     *                  Zero in tests.
     */
    @Autowired
    public MockAiProvider(@Value("${flowcanvas.ai.mock-latency-ms:0}") long latencyMs) {
        this.latencyMs = Math.max(0, latencyMs);
    }

    public MockAiProvider() {
        this(0);
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public AiResponse complete(AiRequest request) {
        long start = System.nanoTime();
        callCount.incrementAndGet();

        if (request.userPrompt().toLowerCase(Locale.ROOT).contains(FAIL_MARKER)) {
            throw new AiProviderException(
                    "Mock provider was asked to fail (prompt contained " + FAIL_MARKER + ").");
        }
        long delay = latencyMs + (request.userPrompt().toLowerCase(Locale.ROOT).contains(SLOW_MARKER) ? 750 : 0);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AiProviderException("Mock provider call was interrupted.", interrupted);
            }
        }

        String content = render(request);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return new AiResponse(content, request.model(), name(), latencyMs);
    }

    /** How many times the mock provider has been invoked — used by tests and the health view. */
    public long callCount() {
        return callCount.get();
    }

    private String render(AiRequest request) {
        String prompt = request.userPrompt().trim();
        String summary = summarize(prompt);
        StringBuilder sb = new StringBuilder();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            sb.append("[system] ").append(request.systemPrompt().trim()).append('\n');
        }
        sb.append("[mock:").append(request.model()).append("] ").append(summary);
        return sb.toString();
    }

    /**
     * Produces a short deterministic summary of the prompt: word count, character count and a
     * stable fingerprint. Deterministic means the same prompt always yields the same output,
     * which keeps the execution history and the golden tests reproducible.
     */
    private String summarize(String prompt) {
        if (prompt.isEmpty()) {
            return "Response to an empty prompt. (no input received)";
        }
        int words = prompt.split("\\s+").length;
        int fingerprint = Math.abs(prompt.hashCode()) % 1000;
        return "Generated a " + words + "-word reply to the prompt "
                + "(\"" + truncate(prompt, 60) + "\"). Fingerprint #" + String.format("%03d", fingerprint) + ".";
    }

    private String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }
}
