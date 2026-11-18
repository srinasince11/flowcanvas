package com.flowcanvas.engine;

import com.flowcanvas.config.AiProperties;
import org.springframework.stereotype.Component;

/** Small holder for the effective AI configuration, injected into the executors. */
@Component
public class AiProviderConfig {

    private final AiProperties properties;

    public AiProviderConfig(AiProperties properties) {
        this.properties = properties;
    }

    public String defaultModel() {
        return properties.model();
    }

    public String providerName() {
        return properties.provider();
    }
}
