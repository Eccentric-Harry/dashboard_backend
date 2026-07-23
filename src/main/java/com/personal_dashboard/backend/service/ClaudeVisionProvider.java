package com.personal_dashboard.backend.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fallback Claude implementation of the VisionProvider strategy.
 * Gemini is the primary provider (see NutritionPipelineService); Claude is used
 * when Gemini errors or is unavailable.
 */
@Component("claudeVisionProvider")
@Slf4j
public class ClaudeVisionProvider implements VisionProvider {

    @Value("${ai.providers.claude.api-key}")
    private String apiKey;

    @Value("${ai.providers.claude.model}")
    private String model;

    private AnthropicClient client;

    @PostConstruct
    private void init() {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    @Override
    public String analyzeFoodImage(List<byte[]> images, String prompt) {
        try {
            List<ContentBlockParam> content = new ArrayList<>();
            int imageCount = 0;
            if (images != null) {
                for (byte[] imageBytes : images) {
                    if (imageBytes == null || imageBytes.length == 0) continue;
                    Base64ImageSource.MediaType mediaType = resolveMediaType(imageBytes);
                    String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                    content.add(ContentBlockParam.ofImage(
                            ImageBlockParam.builder()
                                    .source(
                                            Base64ImageSource.builder()
                                                    .mediaType(mediaType)
                                                    .data(base64Image)
                                                    .build())
                                    .build()));
                    imageCount++;
                }
            }
            // Text prompt goes after the images so the model has the visual context first.
            content.add(ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build()));

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(8192L)
                    // Stage 1/2 prompts already carry their own chain-of-thought scratchpad
                    // instructions (written for non-thinking models); extended thinking would
                    // duplicate that reasoning and add latency/cost for no accuracy gain here.
                    .thinking(ThinkingConfigDisabled.builder().build())
                    .addUserMessageOfBlockParams(content)
                    .build();

            log.info("[ClaudeVisionProvider] Sending request to Claude API (model: {}, images: {})", model, imageCount);
            long startedAt = System.currentTimeMillis();
            Message message = client.messages().create(params);
            long elapsedMs = System.currentTimeMillis() - startedAt;

            String text = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .collect(Collectors.joining());

            if (text.isBlank()) {
                log.error("[ClaudeVisionProvider] No text content in response after {}ms (stop reason: {})", elapsedMs, message.stopReason());
                throw new RuntimeException("Claude API returned no text content (stop reason: " + message.stopReason() + ")");
            }

            log.info("[ClaudeVisionProvider] Received response in {}ms (stop reason: {})", elapsedMs, message.stopReason());
            return extractAndCleanJson(text);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[ClaudeVisionProvider] Vision call failed: {}", e.getMessage());
            throw new RuntimeException("Error executing Claude vision call", e);
        }
    }

    @Override
    public String getProviderName() {
        return "Claude";
    }

    private String extractAndCleanJson(String text) {
        text = text.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }
        return text;
    }

    private Base64ImageSource.MediaType resolveMediaType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return Base64ImageSource.MediaType.IMAGE_JPEG;
        }
        // PNG magic number: 89 50 4E 47
        if (bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50 &&
            bytes[2] == (byte) 0x4E && bytes[3] == (byte) 0x47) {
            return Base64ImageSource.MediaType.IMAGE_PNG;
        }
        // JPEG magic number: FF D8
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return Base64ImageSource.MediaType.IMAGE_JPEG;
        }
        // WebP magic: 'R' 'I' 'F' 'F' ... 'W' 'E' 'B' 'P'
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return Base64ImageSource.MediaType.IMAGE_WEBP;
        }
        return Base64ImageSource.MediaType.IMAGE_JPEG; // default fallback
    }
}
