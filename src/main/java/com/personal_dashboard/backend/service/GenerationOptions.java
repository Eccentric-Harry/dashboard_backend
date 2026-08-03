package com.personal_dashboard.backend.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Per-call generation settings for a {@link VisionProvider}.
 *
 * <p>Exists so the two pipeline stages can be tuned independently — the vision stage wants
 * image detail and shallow reasoning, the narrative stage wants no image at all and a hard
 * output ceiling — instead of sharing one hard-coded block.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationOptions {

    /**
     * Reasoning depth: {@code minimal}, {@code low}, {@code medium} or {@code high}.
     * On Gemini 3.x this supersedes the legacy {@code thinkingBudget} integer.
     */
    @Builder.Default
    private String thinkingLevel = "low";

    /**
     * Image detail: {@code media_resolution_low} (~280 tokens),
     * {@code media_resolution_medium} (~560) or {@code media_resolution_high} (~1120).
     */
    @Builder.Default
    private String mediaResolution = "media_resolution_medium";

    /**
     * Sampling temperature. Gemini 3.x is tuned for 1.0; Google's own guidance warns that
     * values below 1.0 can cause looping or degraded performance on reasoning-heavy tasks,
     * so this should not be lowered in pursuit of determinism.
     */
    @Builder.Default
    private double temperature = 1.0;

    @Builder.Default
    private int maxOutputTokens = 8192;

    /**
     * Optional OpenAPI-subset schema. When set, the API constrains decoding to it, which
     * removes the need to describe the JSON shape in prose and eliminates markdown-fence
     * and truncation parse failures.
     */
    private Map<String, Object> responseSchema;

    /** Label used in cost/telemetry logging to attribute spend to a pipeline stage. */
    @Builder.Default
    private String stageLabel = "unspecified";
}
