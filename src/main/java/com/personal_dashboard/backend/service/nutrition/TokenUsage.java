package com.personal_dashboard.backend.service.nutrition;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Token spend for one model call, and the cost that follows from it.
 *
 * <p>Recorded per stage rather than per analysis because the two stages behave very
 * differently — vision carries the image tokens, narrative carries the thinking — and a
 * single total hides which one to tune when the bill moves.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {

    /** {@code extraction} or {@code narrative}. */
    @JsonProperty("stage")
    private String stage;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("model")
    private String model;

    /** Total prompt tokens, including any served from cache. */
    @JsonProperty("input_tokens")
    private int inputTokens;

    /** Portion of {@link #inputTokens} served from the provider's context cache. */
    @JsonProperty("cached_input_tokens")
    private int cachedInputTokens;

    @JsonProperty("output_tokens")
    private int outputTokens;

    /** Billed at the output rate on Gemini 3.x, so tracked separately to make that visible. */
    @JsonProperty("thinking_tokens")
    private int thinkingTokens;

    @JsonProperty("cost_usd")
    private double costUsd;

    /** True when the stage was served from the repeat-meal cache and cost nothing. */
    @JsonProperty("from_cache")
    private boolean fromCache;

    /**
     * Rolled-up cost for a whole analysis, in both currencies.
     *
     * <p>The rupee figure is what the user actually asked to see; the dollar figure is what
     * reconciles against the provider's invoice.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        @JsonProperty("total_cost_usd")
        private double totalCostUsd;

        @JsonProperty("total_cost_inr")
        private double totalCostInr;

        @JsonProperty("total_input_tokens")
        private int totalInputTokens;

        @JsonProperty("total_output_tokens")
        private int totalOutputTokens;

        @JsonProperty("total_cached_tokens")
        private int totalCachedTokens;

        /** True when no model call was made at all — a full repeat-meal cache hit. */
        @JsonProperty("fully_cached")
        private boolean fullyCached;

        @JsonProperty("usd_to_inr")
        private double usdToInr;

        @JsonProperty("stages")
        @Builder.Default
        private List<TokenUsage> stages = new ArrayList<>();
    }
}
