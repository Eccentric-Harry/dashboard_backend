package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.PostConstruct;

import java.util.*;

/**
 * Primary Gemini implementation of the VisionProvider strategy.
 *
 * <p>Three settings here materially affect both cost and answer quality on Gemini 3.x:</p>
 * <ul>
 *   <li><b>temperature</b> — defaults to 1.0. Google's Gemini 3 guidance warns that values
 *       below 1.0 can cause looping or degraded performance on mathematical and reasoning
 *       tasks, so the previous 0.1 was working against the model rather than stabilising it.</li>
 *   <li><b>thinking_level</b> — the Gemini 3.x replacement for the legacy {@code thinkingBudget}
 *       integer. Thinking tokens bill at the output rate, so this is a real cost lever, but
 *       switching it off entirely and asking for hand-written chain-of-thought in the response
 *       costs the same and reasons worse.</li>
 *   <li><b>media_resolution</b> — roughly 280/560/1120 tokens per image for low/medium/high.
 *       Food photographs do not need the high tier.</li>
 * </ul>
 *
 * <p>Every call logs its token split, including how much was served from the implicit
 * context cache, so spend is attributable per pipeline stage instead of inferred from a bill.</p>
 */
@Component("geminiVisionProvider")
@Slf4j
public class GeminiVisionProvider implements VisionProvider {

    @Value("${ai.providers.gemini.endpoint}")
    private String endpoint;

    @Value("${ai.providers.gemini.api-key}")
    private String apiKey;

    @Value("${ai.providers.gemini.model}")
    private String model;

    @Value("${ai.providers.gemini.price.input-per-million:1.50}")
    private double priceInputPerMillion;

    @Value("${ai.providers.gemini.price.output-per-million:9.00}")
    private double priceOutputPerMillion;

    @Value("${ai.providers.gemini.price.cached-input-per-million:0.15}")
    private double priceCachedInputPerMillion;

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @PostConstruct
    private void init() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String analyzeFoodImage(byte[] imageBytes, String prompt, GenerationOptions options) {
        try {
            GenerationOptions opts = options != null ? options : GenerationOptions.builder().build();

            List<Map<String, Object>> parts = new ArrayList<>();
            // The prompt goes first so its stable prefix can be served from the implicit
            // context cache; putting variable content ahead of it would defeat caching.
            parts.add(Map.of("text", prompt));

            if (imageBytes != null && imageBytes.length > 0) {
                String mimeType = resolveMimeType(imageBytes);
                String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                parts.add(Map.of(
                        "inline_data", Map.of(
                                "mime_type", mimeType,
                                "data", base64Image)));
            }

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("temperature", opts.getTemperature());
            generationConfig.put("maxOutputTokens", opts.getMaxOutputTokens());
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("thinkingConfig", Map.of("thinkingLevel", opts.getThinkingLevel()));
            if (imageBytes != null && imageBytes.length > 0 && opts.getMediaResolution() != null) {
                generationConfig.put("mediaResolution", opts.getMediaResolution());
            }
            if (opts.getResponseSchema() != null) {
                generationConfig.put("responseSchema", opts.getResponseSchema());
            }

            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", parts)),
                    "generationConfig", generationConfig);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            log.info("[GeminiVisionProvider] stage={} model={} thinking={} mediaRes={} schema={}",
                    opts.getStageLabel(), model, opts.getThinkingLevel(),
                    imageBytes != null ? opts.getMediaResolution() : "n/a",
                    opts.getResponseSchema() != null);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(endpoint + "?key=" + apiKey, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Gemini API call failed with status: " + response.getStatusCode());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            logUsage(root, opts.getStageLabel());
            return extractAndCleanJson(root);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error executing Gemini vision call", e);
        }
    }

    @Override
    public String getProviderName() {
        return "Gemini";
    }

    /**
     * Emits the per-call token split and its estimated cost.
     *
     * <p>{@code cachedContentTokenCount} is the portion of input served from the implicit
     * context cache at roughly a tenth of the normal input rate — the number to watch when
     * verifying that a prompt's static prefix is actually being reused.</p>
     */
    private void logUsage(JsonNode root, String stage) {
        JsonNode usage = root.path("usageMetadata");
        if (usage.isMissingNode()) return;

        int promptTokens = usage.path("promptTokenCount").asInt(0);
        int cachedTokens = usage.path("cachedContentTokenCount").asInt(0);
        int outputTokens = usage.path("candidatesTokenCount").asInt(0);
        int thoughtTokens = usage.path("thoughtsTokenCount").asInt(0);

        int billedInput = Math.max(promptTokens - cachedTokens, 0);
        // Thinking tokens bill at the output rate on Gemini 3.x.
        int billedOutput = outputTokens + thoughtTokens;

        double usd = billedInput * priceInputPerMillion / 1_000_000.0
                + cachedTokens * priceCachedInputPerMillion / 1_000_000.0
                + billedOutput * priceOutputPerMillion / 1_000_000.0;

        log.info("[GeminiCost] stage={} in={} (cached={}) out={} (thinking={}) estUsd={} ",
                stage, promptTokens, cachedTokens, outputTokens, thoughtTokens,
                String.format("%.5f", usd));
    }

    private String extractAndCleanJson(JsonNode root) {
        String text = root.path("candidates").get(0)
                .path("content").path("parts").get(0)
                .path("text").asText();

        text = text.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }
        return text;
    }

    private String resolveMimeType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "image/jpeg";
        }
        // PNG magic number: 89 50 4E 47
        if (bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50 &&
            bytes[2] == (byte) 0x4E && bytes[3] == (byte) 0x47) {
            return "image/png";
        }
        // JPEG magic number: FF D8
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        // WebP magic: 'R' 'I' 'F' 'F' ... 'W' 'E' 'B' 'P'
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "image/webp";
        }
        return "image/jpeg"; // default fallback
    }
}
