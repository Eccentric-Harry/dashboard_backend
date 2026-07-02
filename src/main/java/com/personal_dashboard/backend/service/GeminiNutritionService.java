package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import com.personal_dashboard.backend.model.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the automated two-stage Gemini AI meal analysis pipeline:
 *
 * Stage 1 — Vision/Parsing Layer:
 *   Input:  Image (base64 inline) OR raw text description
 *   Model:  gemini-2.0-flash
 *   Output: Intermediate JSON {"items":[{"name":"avocado","quantity":"100g"}]}
 *
 * Stage 2 — Clinical/Nutrition Layer:
 *   Input:  Stage 1 JSON + full user profile (age, gender, height, weight, TDEE, targets, conditions)
 *   Model:  gemini-2.0-flash
 *   Output: Full nutrition analysis matching GeminiAnalysisResult contract
 */
@Service
@Slf4j
public class GeminiNutritionService {

    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent";

    @Value("${gemini.api.key:GEMINI_KEY_NOT_SET}")
    private String apiKey;

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @PostConstruct
    private void init() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    // ─── Public Entry Point ────────────────────────────────────────────

    /**
     * Runs the full two-stage pipeline.
     *
     * @param imageFile       Optional uploaded image file
     * @param textDescription Optional text description of the meal
     * @param userProfile     User profile for personalised nutrition targets and medical context
     * @return Full GeminiAnalysisResult from Stage 2
     */
    public GeminiAnalysisResult analyzeWithTwoStage(
            MultipartFile imageFile,
            String textDescription,
            UserAccount userProfile) throws Exception {

        log.info("[GeminiPipeline] Starting Stage 1 — food identification");
        String stage1Json = runStage1Vision(imageFile, textDescription);
        log.info("[GeminiPipeline] Stage 1 complete. Items: {}", stage1Json);

        log.info("[GeminiPipeline] Starting Stage 2 — clinical nutrition analysis");
        GeminiAnalysisResult result = runStage2Nutrition(stage1Json, userProfile);
        log.info("[GeminiPipeline] Stage 2 complete. Quality: {}",
                result.getOverallAssessment() != null ? result.getOverallAssessment().getMealQuality() : "N/A");

        return result;
    }

    // ─── Stage 1 ──────────────────────────────────────────────────────

    private String runStage1Vision(MultipartFile imageFile, String textDescription) throws Exception {
        List<Map<String, Object>> parts = new ArrayList<>();

        // Add text instruction part
        String textInstruction = """
                You are a precise food identification assistant.
                Analyse the provided meal image and/or description.
                Identify all distinct food items and estimate their portion sizes/weights.
                
                Return ONLY a valid JSON object — no markdown, no explanation, no extra text:
                {
                  "items": [
                    { "name": "<food item name>", "quantity": "<estimated amount with unit>" }
                  ]
                }
                """;

        if (textDescription != null && !textDescription.isBlank()) {
            textInstruction += "\n\nMeal description provided by the user: " + textDescription;
        }

        parts.add(Map.of("text", textInstruction));

        // Attach image as inline base64 data if provided
        if (imageFile != null && !imageFile.isEmpty()) {
            String mimeType = resolveMimeType(imageFile.getContentType(), imageFile.getOriginalFilename());
            String base64Image = Base64.getEncoder().encodeToString(imageFile.getBytes());
            parts.add(Map.of(
                    "inline_data", Map.of(
                            "mime_type", mimeType,
                            "data", base64Image)));
        }

        String rawResponse = callGemini(parts);
        return extractJsonFromResponse(rawResponse);
    }

    // ─── Stage 2 ──────────────────────────────────────────────────────

    private GeminiAnalysisResult runStage2Nutrition(String stage1Json, UserAccount user) throws Exception {
        String userProfileSummary = buildUserProfileSummary(user);

        String prompt = """
                You are an expert clinical nutritionist AI.
                
                USER PROFILE:
                %s
                
                IDENTIFIED FOOD ITEMS FROM VISUAL ANALYSIS:
                %s
                
                TASK:
                1. Calculate precise macro and micro nutrition for each item listed.
                2. Sum up meal totals.
                3. Calculate percentage of user's daily targets consumed by this meal.
                4. Evaluate any medical risks based on the user's conditions (if none listed, skip medicalAnalysis array).
                5. Provide an overall meal assessment.
                
                Return ONLY a valid JSON object with this EXACT structure — no markdown, no explanation:
                {
                  "meal_items": [
                    {
                      "name": "",
                      "serving_size": "",
                      "confidence": "high|medium|low",
                      "calories": 0,
                      "protein": 0,
                      "carbs": 0,
                      "fat": 0,
                      "fiber": 0,
                      "sugar": 0,
                      "sodium": 0,
                      "saturated_fat": 0
                    }
                  ],
                  "meal_totals": {
                    "calories": 0,
                    "protein": 0,
                    "carbs": 0,
                    "fat": 0,
                    "fiber": 0,
                    "sugar": 0,
                    "sodium": 0
                  },
                  "daily_target_progress": {
                    "calories_pct": 0,
                    "protein_pct": 0,
                    "carbs_pct": 0,
                    "fat_pct": 0,
                    "fiber_pct": 0
                  },
                  "medical_analysis": [
                    {
                      "condition": "",
                      "risk": "low|moderate|high",
                      "findings": [],
                      "recommendations": []
                    }
                  ],
                  "overall_assessment": {
                    "meal_quality": "excellent|good|fair|poor",
                    "fitness_alignment": "",
                    "strengths": [],
                    "concerns": [],
                    "improvements": []
                  }
                }
                """.formatted(userProfileSummary, stage1Json);

        List<Map<String, Object>> parts = List.of(Map.of("text", prompt));
        String rawResponse = callGemini(parts);
        String jsonStr = extractJsonFromResponse(rawResponse);

        return objectMapper.readValue(jsonStr, GeminiAnalysisResult.class);
    }

    // ─── Gemini REST API Call ──────────────────────────────────────────

    private String callGemini(List<Map<String, Object>> parts) {
        String url = GEMINI_API_BASE + "?key=" + apiKey;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", Map.of(
                        "temperature", 0.1,
                        "maxOutputTokens", 4096));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Gemini API call failed with status: " + response.getStatusCode());
        }
        return response.getBody();
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Extracts the text content from the Gemini API response JSON.
     * The response structure is: candidates[0].content.parts[0].text
     */
    private String extractJsonFromResponse(String geminiApiResponse) throws Exception {
        JsonNode root = objectMapper.readTree(geminiApiResponse);
        String text = root.path("candidates").get(0)
                .path("content").path("parts").get(0)
                .path("text").asText();

        // Strip markdown code fences if Gemini wraps in ```json ... ```
        text = text.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }
        return text;
    }

    /**
     * Builds a human-readable user profile summary string for the Stage 2 prompt.
     */
    private String buildUserProfileSummary(UserAccount user) {
        if (user == null) return "No user profile available.";

        StringBuilder sb = new StringBuilder();
        if (user.getPhysicalMetrics() != null) {
            UserAccount.PhysicalMetrics pm = user.getPhysicalMetrics();
            if (pm.getAge() != null) sb.append("Age: ").append(pm.getAge()).append("\n");
            if (pm.getGender() != null) sb.append("Gender: ").append(pm.getGender()).append("\n");
            if (pm.getHeight() != null) sb.append("Height: ").append(pm.getHeight()).append(" cm\n");
            if (pm.getWeight() != null) sb.append("Weight: ").append(pm.getWeight()).append(" kg\n");
        } else {
            if (user.getAge() != null) sb.append("Age: ").append(user.getAge()).append("\n");
            if (user.getWeight() != null) sb.append("Weight: ").append(user.getWeight()).append(" kg\n");
            if (user.getHeight() != null) sb.append("Height: ").append(user.getHeight()).append(" cm\n");
        }

        if (user.getTdee() != null) sb.append("TDEE: ").append(Math.round(user.getTdee())).append(" kcal\n");

        if (user.getDynamicTargets() != null) {
            UserAccount.DynamicTargets dt = user.getDynamicTargets();
            if (dt.getCalculatedCalories() != null) sb.append("Daily Calorie Target: ").append(dt.getCalculatedCalories()).append(" kcal\n");
            if (dt.getCalculatedProtein() != null) sb.append("Daily Protein Target: ").append(dt.getCalculatedProtein()).append(" g\n");
            if (dt.getCalculatedCarbs() != null) sb.append("Daily Carbs Target: ").append(dt.getCalculatedCarbs()).append(" g\n");
            if (dt.getCalculatedFat() != null) sb.append("Daily Fat Target: ").append(dt.getCalculatedFat()).append(" g\n");
        } else {
            if (user.getTargetCalories() != null) sb.append("Daily Calorie Target: ").append(user.getTargetCalories()).append(" kcal\n");
            if (user.getTargetProtein() != null) sb.append("Daily Protein Target: ").append(user.getTargetProtein()).append(" g\n");
        }

        if (user.getFitnessGoal() != null) sb.append("Fitness Goal: ").append(user.getFitnessGoal()).append("\n");
        if (user.getActivityLevel() != null) sb.append("Activity Level: ").append(user.getActivityLevel()).append("\n");

        if (user.getMedicalConditions() != null && !user.getMedicalConditions().isEmpty()) {
            sb.append("Medical Conditions: ").append(String.join(", ", user.getMedicalConditions())).append("\n");
        } else {
            sb.append("Medical Conditions: None reported\n");
        }

        return sb.toString();
    }

    /**
     * Resolves MIME type for the image, falling back to filename extension.
     */
    private String resolveMimeType(String contentType, String filename) {
        if (contentType != null && contentType.startsWith("image/")) {
            return contentType;
        }
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".heic")) return "image/heic";
        }
        return "image/jpeg"; // safe fallback
    }
}
