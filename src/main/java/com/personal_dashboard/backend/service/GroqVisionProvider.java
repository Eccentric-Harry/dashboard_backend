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
 * Fallback Groq implementation of the VisionProvider strategy.
 * Uses OpenAI-compatible message layout and Bearer Auth header.
 */
@Component("groqVisionProvider")
@Slf4j
public class GroqVisionProvider implements VisionProvider {

    @Value("${ai.providers.groq.endpoint}")
    private String endpoint;

    @Value("${ai.providers.groq.api-key}")
    private String apiKey;

    @Value("${ai.providers.groq.model}")
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
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");

            if (imageBytes != null && imageBytes.length > 0) {
                String mimeType = resolveMimeType(imageBytes);
                String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                String imageUrl = "data:" + mimeType + ";base64," + base64Image;

                List<Map<String, Object>> contentList = List.of(
                        Map.of("type", "text", "text", prompt),
                        Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
                );
                message.put("content", contentList);
            } else {
                message.put("content", prompt);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(message));
            body.put("temperature", 0.1);
            body.put("max_tokens", 4096);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            log.info("[GroqVisionProvider] Sending request to Groq OpenAI-compatible API (model: {})", model);
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Groq API call failed with status: " + response.getStatusCode());
            }

            return extractAndCleanJson(response.getBody());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error executing Groq vision call", e);
        }
    }

    @Override
    public String getProviderName() {
        return "Groq";
    }

    private String extractAndCleanJson(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        String text = root.path("choices").get(0)
                .path("message").path("content").asText();

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
