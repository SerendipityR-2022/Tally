package com.example.budgetapp.ai;

import org.junit.Test;
import static org.junit.Assert.*;

public class AiConfigEndpointTest {
    @Test public void modelDiscoveryCanReuseExactExistingFallbackSemantics() {
        AiConfig config = new AiConfig();
        config.baseUrl = "shared.example.test"; config.apiKey = "shared-key";
        assertEquals("shared.example.test", config.getEffectiveTextBaseUrl());
        assertEquals("shared-key", config.getEffectiveVisionApiKey());
        config.textBaseUrl = "text.example.test/v1"; config.textApiKey = "text-key";
        assertEquals("text.example.test/v1", config.getEffectiveVisionBaseUrl());
        assertEquals("text-key", config.getEffectiveAudioApiKey());
        config.visionBaseUrl = "vision.example.test/v1";
        config.audioApiKey = "audio-key";
        assertEquals("vision.example.test/v1", config.getEffectiveVisionBaseUrl());
        assertEquals("text-key", config.getEffectiveVisionApiKey());
        assertEquals("text.example.test/v1", config.getEffectiveAudioBaseUrl());
        assertEquals("audio-key", config.getEffectiveAudioApiKey());
    }
}
