package com.personal_dashboard.backend.service;

import java.util.List;

/**
 * Interface representing a core multimodal vision provider.
 */
public interface VisionProvider {
    /**
     * Analyzes one or more food images and/or a text prompt and returns the parsed
     * JSON string response.
     *
     * @param images Optional list of raw image byte arrays (up to 3 for a meal scan);
     *               null or empty for a text-only call
     * @param prompt Structured prompt/instructions
     * @return Extracted JSON response string
     */
    String analyzeFoodImage(List<byte[]> images, String prompt);

    /**
     * Returns the user-friendly name of the provider (e.g. "Claude", "Gemini").
     *
     * @return Provider name
     */
    String getProviderName();
}
