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

    /**
     * Whether this provider has a usable API key and can actually be called.
     *
     * <p>Exists so the pipeline can skip a fallback that would only fail with an
     * authentication error, which would otherwise mask the primary provider's real
     * failure. A deployment running Gemini-only is a supported configuration, not a
     * misconfiguration.
     *
     * @return true if a call could plausibly succeed
     */
    boolean isConfigured();
}
