package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.PostConstruct;

import java.util.*;

/**
 * Primary Gemini implementation of the VisionProvider strategy.
 * Claude is the fallback provider (see NutritionPipelineService).
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

    /** Hard ceiling on generated tokens, including Gemini's own thinking tokens. */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @PostConstruct
    private void init() {
        // Bound each Gemini call so a stuck upstream can never hang the request
        // thread indefinitely (which would exhaust the pool and stall other users).
        // Timeouts are generous — well above normal vision latency — so they only
        // trip on genuine hangs, not slow-but-healthy responses.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);   // 15s to establish the connection
        factory.setReadTimeout(120_000);      // 120s ceiling per stage response
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String analyzeFoodImage(List<byte[]> images, String prompt) {
        try {
            List<Map<String, Object>> parts = new ArrayList<>();
            parts.add(Map.of("text", prompt));

            int imageCount = 0;
            if (images != null) {
                for (byte[] imageBytes : images) {
                    if (imageBytes == null || imageBytes.length == 0) continue;
                    String mimeType = resolveMimeType(imageBytes);
                    String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                    parts.add(Map.of(
                            "inline_data", Map.of(
                                    "mime_type", mimeType,
                                    "data", base64Image)));
                    imageCount++;
                }
            }

            String url = endpoint + "?key=" + apiKey;

            // Gemini 3.x uses thinkingLevel (minimal|low|medium|high); thinkingBudget was
            // removed and 0 (full thinking-off) is rejected with INVALID_ARGUMENT — 3.x Flash
            // cannot disable thinking. "minimal" keeps latency/cost low. Thinking tokens are
            // drawn from maxOutputTokens.
            //
            // The cap is a cost guardrail, not a target: the trimmed Stage-2 schema lands
            // well under 8k output tokens, so anything approaching this ceiling is a runaway
            // generation we would rather truncate than pay for in full.
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", parts)),
                    "generationConfig", Map.of(
                            "temperature", 0.1,
                            "maxOutputTokens", MAX_OUTPUT_TOKENS,
                            "responseMimeType", "application/json",
                            "thinkingConfig", Map.of(
                                    "thinkingLevel", "minimal"
                            )));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            log.info("[GeminiVisionProvider] Sending request to Gemini API (model: {}, images: {})", model, imageCount);
            long startedAt = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            long elapsedMs = System.currentTimeMillis() - startedAt;

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("[GeminiVisionProvider] API call failed with status: {} after {}ms", response.getStatusCode(), elapsedMs);
                throw new RuntimeException("Gemini API call failed with status: " + response.getStatusCode());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            logUsage(root, elapsedMs);
            return extractAndCleanJson(root);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GeminiVisionProvider] Vision call failed: {}", e.getMessage());
            throw new RuntimeException("Error executing Gemini vision call", e);
        }
    }

    @Override
    public String getProviderName() {
        return "Gemini";
    }

    /**
     * Emits the per-call token bill so spend is observable in the logs. {@code cached}
     * counts prefix tokens served from Gemini's implicit context cache — if that stays
     * at zero across scans, something is varying inside the supposedly static prompt
     * prefix and the caching win has been lost.
     */
    private void logUsage(JsonNode root, long elapsedMs) {
        JsonNode usage = root.path("usageMetadata");
        if (usage.isMissingNode()) {
            log.info("[GeminiVisionProvider] Received response in {}ms (no usage metadata)", elapsedMs);
            return;
        }
        log.info("[GeminiVisionProvider] Received response in {}ms — tokens: prompt={} (cached={}), thinking={}, output={}, total={}",
                elapsedMs,
                usage.path("promptTokenCount").asInt(),
                usage.path("cachedContentTokenCount").asInt(),
                usage.path("thoughtsTokenCount").asInt(),
                usage.path("candidatesTokenCount").asInt(),
                usage.path("totalTokenCount").asInt());
    }

    private String extractAndCleanJson(JsonNode root) throws Exception {
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
