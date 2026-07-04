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

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @PostConstruct
    private void init() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String analyzeFoodImage(byte[] imageBytes, String prompt) {
        try {
            List<Map<String, Object>> parts = new ArrayList<>();
            parts.add(Map.of("text", prompt));

            if (imageBytes != null && imageBytes.length > 0) {
                String mimeType = resolveMimeType(imageBytes);
                String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                parts.add(Map.of(
                        "inline_data", Map.of(
                                "mime_type", mimeType,
                                "data", base64Image)));
            }

            String url = endpoint + "?key=" + apiKey;

            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", parts)),
                    "generationConfig", Map.of(
                            "temperature", 0.1,
                            "maxOutputTokens", 8192,
                            "responseMimeType", "application/json",
                            "thinkingConfig", Map.of(
                                    "thinkingBudget", 0
                            )));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            log.info("[GeminiVisionProvider] Sending request to Gemini API (model: {})", model);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Gemini API call failed with status: " + response.getStatusCode());
            }

            return extractAndCleanJson(response.getBody());
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

    private String extractAndCleanJson(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
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
