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
     * Cache-aware variant. {@code staticPrefix} is the byte-identical rules block shared
     * by every call (persona, clinical mandates, scoring rubric, output schema);
     * {@code volatileSuffix} is the per-request tail (user profile, extracted ingredients).
     *
     * <p>Prompt caching is a strict prefix match, so keeping the stable half first and
     * flagging the boundary lets a provider bill the shared prefix at cache-read rates
     * instead of full price on every scan. Providers without caching simply concatenate.
     *
     * @param images         Optional raw image byte arrays
     * @param staticPrefix   Stable instructions — identical across requests
     * @param volatileSuffix Per-request content, appended after the prefix
     * @return Extracted JSON response string
     */
    default String analyzeFoodImage(List<byte[]> images, String staticPrefix, String volatileSuffix) {
        return analyzeFoodImage(images, staticPrefix + volatileSuffix);
    }

    /**
     * Returns the user-friendly name of the provider (e.g. "Claude", "Gemini").
     *
     * @return Provider name
     */
    String getProviderName();
}
