package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;

/**
 * Generates a stylised pastel, soft-lit, top-view thumbnail of a logged dish via
 * Google's Gemini image model (gemini-3.1-flash-image by default), returning it as
 * a self-contained {@code data:} URI.
 *
 * The URI is stored directly on the MealEntry (MongoDB), so the generated image
 * travels with the meal — no external asset hosting or writable disk required.
 * If generation fails for any reason this returns {@code null} and the caller
 * falls back to the keyword-based bundled asset (see food-image-helper.ts).
 */
@Service
@Slf4j
public class GeminiImageGenerationService {

    @Value("${ai.providers.gemini.image-endpoint}")
    private String imageEndpoint;

    @Value("${ai.providers.gemini.image-model}")
    private String imageModel;

    @Value("${ai.providers.gemini.api-key}")
    private String apiKey;

    /**
     * Feature flag for the pastel dish-image step. Disabled by default; flip
     * {@code GEMINI_IMAGE_GENERATION_ENABLED=true} (or the property) to turn it
     * back on without any code change. When off, meals fall back to the bundled
     * keyword-matched asset on the frontend, exactly as they do on a failure.
     */
    @Value("${ai.providers.gemini.image-generation.enabled:false}")
    private boolean imageGenerationEnabled;

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @PostConstruct
    private void init() {
        // Image generation is best-effort, so bound it tightly: if the model is slow
        // we would rather fall back to the bundled asset than inflate the total
        // request time (and risk tripping an upstream proxy timeout on the caller).
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);   // 15s to establish the connection
        factory.setReadTimeout(90_000);       // 90s ceiling — then fall back to a bundled image
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Builds a pastel food image for the given dish and returns a PNG {@code data:} URI,
     * or {@code null} if the dish is blank or generation fails.
     *
     * @param dishDescription human-readable dish name / description (e.g. "Masala dosa with chutney")
     * @return {@code data:image/png;base64,...} URI, or {@code null} on any failure
     */
    public String generatePastelFoodImageDataUri(String dishDescription) {
        if (!imageGenerationEnabled) {
            log.debug("[GeminiImageGen] Image generation disabled — using bundled asset fallback");
            return null;
        }
        if (dishDescription == null || dishDescription.isBlank()) {
            return null;
        }

        try {
            String prompt = buildImagePrompt(dishDescription.trim());

            // Gemini image models (Nano Banana 2 / gemini-3.1-flash-image) require BOTH
            // TEXT and IMAGE response modalities; the image arrives as an inline_data part.
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                            "responseModalities", List.of("TEXT", "IMAGE"),
                            "temperature", 0.4));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            String url = imageEndpoint + "?key=" + apiKey;

            log.info("[GeminiImageGen] Generating pastel image (model: {}) for dish: {}", imageModel, dishDescription);
            long startedAt = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            long elapsedMs = System.currentTimeMillis() - startedAt;

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[GeminiImageGen] Image API returned {} after {}ms — falling back to bundled asset",
                        response.getStatusCode(), elapsedMs);
                return null;
            }

            String dataUri = extractImageDataUri(response.getBody());
            if (dataUri == null) {
                log.warn("[GeminiImageGen] No inline image data in response after {}ms — falling back to bundled asset", elapsedMs);
            } else {
                log.info("[GeminiImageGen] Image generated in {}ms ({} bytes base64)", elapsedMs, dataUri.length());
            }
            return dataUri;
        } catch (Exception e) {
            // Image generation is best-effort — never fail the meal log because of it.
            log.warn("[GeminiImageGen] Image generation failed ({}) — falling back to bundled asset", e.getMessage());
            return null;
        }
    }

    private String buildImagePrompt(String dish) {
        return """
                A single appetising vegetarian serving of "%s", photographed from directly
                above (flat top-down view) on a clean off-white ceramic plate against a soft
                neutral pastel background. Soft, diffused natural lighting, gentle shadows,
                muted pastel colour palette, minimalist food styling, no text, no watermark,
                no cutlery clutter, no hands. Square composition, centered, appetising and
                clean editorial look.
                """.formatted(dish);
    }

    /**
     * Extracts the first inline image part from a Gemini generateContent response and
     * returns it as a {@code data:} URI. Scans all parts defensively because the model
     * may emit a text part alongside the image.
     */
    private String extractImageDataUri(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) {
            return null;
        }
        for (JsonNode part : parts) {
            // Gemini may use either "inlineData" (camelCase) or "inline_data" (snake_case).
            JsonNode inline = part.has("inlineData") ? part.path("inlineData") : part.path("inline_data");
            if (inline.isMissingNode() || inline.isNull()) continue;
            String data = inline.path("data").asText(null);
            if (data == null || data.isBlank()) continue;
            String mimeType = inline.path("mimeType").asText(inline.path("mime_type").asText("image/png"));
            return "data:" + mimeType + ";base64," + data;
        }
        return null;
    }
}
