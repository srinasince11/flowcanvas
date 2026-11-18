package com.flowcanvas.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiPropertiesTest {

    @Test
    @DisplayName("never includes the API key when printed or logged")
    void redactsKeyInToString() {
        String secret = "fake-test-key-THIS-MUST-NEVER-APPEAR-IN-LOGS";
        AiProperties properties = new AiProperties("openai", "https://example.test/v1", secret, "m", 30, 2);

        assertThat(properties.toString()).doesNotContain(secret).doesNotContain("NEVER-APPEAR").contains("<redacted>");
        assertThat(properties.apiKey()).isEqualTo(secret);
    }

    @Test
    @DisplayName("says plainly when no key is configured")
    void reportsMissingKey() {
        assertThat(new AiProperties("mock", null, "", null, 0, 0).toString()).contains("apiKey=<not set>");
    }
}
