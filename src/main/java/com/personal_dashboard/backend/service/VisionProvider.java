package com.personal_dashboard.backend.service;

/**
 * Interface representing a core multimodal vision provider.
 */
public interface VisionProvider {
    /**
     * Analyzes a food image and/or text prompt and returns the parsed JSON string response.
     *
     * @param imageBytes Optional raw image bytes
     * @param prompt     Structured prompt/instructions
     * @return Extracted JSON response string
     */
    String analyzeFoodImage(byte[] imageBytes, String prompt);

    /**
     * Returns the user-friendly name of the provider (e.g. "Gemini", "Groq").
     *
     * @return Provider name
     */
    String getProviderName();
}
