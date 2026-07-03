package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import com.personal_dashboard.backend.model.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;

/**
 * Orchestrates the automated two-stage meal analysis pipeline using a multi-provider
 * fallback architecture.
 *
 * Stage 1 — Vision/Parsing Layer:
 *   Input:  Image (base64 inline) OR raw text description
 *   Output: Intermediate JSON {"items":[{"name":"avocado","quantity":"100g"}]}
 *
 * Stage 2 — Clinical/Nutrition Layer:
 *   Input:  Stage 1 JSON + full user profile (age, gender, height, weight, TDEE, targets, conditions)
 *   Output: Full nutrition analysis matching GeminiAnalysisResult contract
 */
@Service
@Slf4j
public class NutritionPipelineService {

    private final VisionProvider primaryProvider;
    private final VisionProvider fallbackProvider;
    private ObjectMapper objectMapper;

    public NutritionPipelineService(
            @Qualifier("geminiVisionProvider") VisionProvider primaryProvider,
            @Qualifier("groqVisionProvider") VisionProvider fallbackProvider) {
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
    }

    @PostConstruct
    private void init() {
        this.objectMapper = new ObjectMapper();
    }

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

        log.info("[NutritionPipeline] Starting Stage 1 — food identification");
        byte[] imageBytes = (imageFile != null && !imageFile.isEmpty()) ? imageFile.getBytes() : null;

        String textInstruction = buildStage1Prompt(textDescription);
        String stage1Json = processMealAnalysis(imageBytes, textInstruction);
        log.info("[NutritionPipeline] Stage 1 complete. Items: {}", stage1Json);

        log.info("[NutritionPipeline] Starting Stage 2 — clinical nutrition analysis");
        String stage2Prompt = buildStage2Prompt(stage1Json, userProfile);
        String stage2Json = processMealAnalysis(null, stage2Prompt);
        log.info("[NutritionPipeline] Stage 2 complete.");

        return objectMapper.readValue(stage2Json, GeminiAnalysisResult.class);
    }

    /**
     * Executes the primary provider (Gemini) with fallback to Groq on 429 Too Many Requests.
     * Uses a default Stage 1 prompt for meal analysis.
     *
     * @param imageBytes Optional raw image bytes
     * @return Extracted JSON response string
     */
    public String processMealAnalysis(byte[] imageBytes) {
        String defaultPrompt = buildStage1Prompt(null);
        return processMealAnalysis(imageBytes, defaultPrompt);
    }

    /**
     * Executes the primary provider (Gemini) with fallback to Groq on 429 Too Many Requests.
     *
     * @param imageBytes Optional raw image bytes
     * @param prompt     Structured prompt/instructions
     * @return Extracted JSON response string
     */
    public String processMealAnalysis(byte[] imageBytes, String prompt) {
        try {
            log.info("[NutritionPipeline] Invoking primary provider ({})", primaryProvider.getProviderName());
            return primaryProvider.analyzeFoodImage(imageBytes, prompt);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("[MealAnalysis] Gemini rate-limit hit. Switching to fallback provider: Groq.");
            return fallbackProvider.analyzeFoodImage(imageBytes, prompt);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("[MealAnalysis] Gemini rate-limit hit. Switching to fallback provider: Groq.");
                return fallbackProvider.analyzeFoodImage(imageBytes, prompt);
            }
            throw e;
        }
    }

    // ─── Prompt Builders ──────────────────────────────────────────────

    private String buildStage1Prompt(String textDescription) {
        String prompt = """
                You are a precise clinical food identification AI trained on the USDA FoodData Central database.
                
                Analyze the image and text description with extreme attention to detail:
                1. Identify every food item, beverage, dressing, sauce, and garnish.
                2. Map identified items to standardized clinical nomenclature (e.g., instead of "idli", use "steamed white rice and urad dal batter cake").
                3. You must account for "hidden" calories and macros. If an item is traditionally fried, sautéed, or fermented, explicitly include the cooking oils, fats, and sodium (e.g., South Indian chutneys and batters require salt; stir-fries require oil).
                4. Estimate weights in grams (g) or milliliters (ml) using standard clinical portion sizes.
                
                Return ONLY a valid JSON object. No markdown, no conversational text:
                {
                  "items": [
                    { "name": "<standardized food name>", "quantity": "<estimated amount with unit>" }
                  ]
                }
                """;

        if (textDescription != null && !textDescription.isBlank()) {
            prompt += "\n\nUser's supplementary description: " + textDescription;
        }
        return prompt;
    }

    private String buildStage2Prompt(String stage1Json, UserAccount user) {
        String userProfileSummary = buildUserProfileSummary(user);

        return """
                You are a Registered Dietitian and Clinical Endocrinologist AI.
                
                Your task is to analyze a meal using guidelines from the World Health Organization (WHO), American Heart Association (AHA), and American Diabetes Association (ADA).
                
                CRITICAL RULES:
                - Mathematical Consistency: The sum of the macros in 'meal_items' MUST exactly match 'meal_totals'. 1g Protein = 4 kcal, 1g Carb = 4 kcal, 1g Fat = 9 kcal.
                - Sodium Reality Check: Savory, fermented, or pickled foods (like cheeses, soy sauce, pickles, and traditional batters) inherently contain high sodium. Do not hallucinate single-digit sodium values for these items.
                - Glycemic Index (GI): Treat refined carbohydrates (white rice, white flour) with the same physiological scrutiny as refined sugars regarding insulin response and skin/metabolic impact.
                
                USER PROFILE:
                %s
                
                IDENTIFIED FOOD ITEMS:
                %s
                
                You MUST use the '_reasoning_scratchpad' field first to write out your clinical evaluation, macro math, and medical correlations before populating the final data arrays.
                
                Return ONLY a valid JSON object with this EXACT structure:
                {
                  "_reasoning_scratchpad": "Write your step-by-step clinical math and medical reasoning here before populating the rest of the JSON. Reference USDA/WHO/AHA standards.",
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
                    "calories_pct": 0.0,
                    "protein_pct": 0.0,
                    "carbs_pct": 0.0,
                    "fat_pct": 0.0,
                    "fiber_pct": 0.0
                  },
                  "medical_analysis": [
                    {
                      "condition": "",
                      "risk": "low|moderate|high",
                      "findings": [ "Clinical finding 1 based on AHA/ADA guidelines", "Finding 2" ],
                      "recommendations": [ "Actionable dietary fix 1", "Fix 2" ]
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
    }

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
}
