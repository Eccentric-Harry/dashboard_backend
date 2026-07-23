package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import com.personal_dashboard.backend.model.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;

/**
 * NutritionPipelineService
 *
 * Orchestrates the two-stage LLM meal-analysis pipeline:
 *
 *   Stage 1 — Vision/Extraction  (multimodal model: Gemini / Claude fallback)
 *             Analyses up to 3 food images and/or a text description, maps every visible
 *             and HIDDEN ingredient to USDA FoodData Central nomenclature, and emits a
 *             raw ingredient JSON array with per-item gram weights.
 *
 *   Stage 2 — Clinical Reasoning (text-only model: Gemini / Claude fallback)
 *             Consumes the Stage-1 JSON plus the fully-populated UserAccount profile,
 *             runs strict macro math (1g P=4 kcal, 1g C=4 kcal, 1g F=9 kcal),
 *             applies WHO / AHA / ADA clinical guidelines, and returns a deep
 *             assessment JSON including glycaemic impact, medical-condition flags,
 *             and actionable recommendations.
 *
 * Hallucination defences embedded in every prompt:
 *   • Hidden-ingredient forcing (oil, salt, batter, ghee, coconut milk, etc.)
 *   • USDA FoodData Central nomenclature anchoring (Stage 1)
 *   • WHO/AHA/ADA guideline anchoring (Stage 2)
 *   • Glycaemic-reality clause (white rice / refined carbs = insulin spike)
 *   • Strict macro-to-calorie math verification gate
 *   • Chain-of-Thought scratchpad emitted BEFORE numeric fields
 *   • Explicit null-is-forbidden and no-hallucination contracts
 */
@Service
@Slf4j
public class NutritionPipelineService {

    private final VisionProvider primaryProvider;
    private final VisionProvider fallbackProvider;
    private ObjectMapper objectMapper;

    public NutritionPipelineService(
            @Qualifier("geminiVisionProvider") VisionProvider primaryProvider,
            @Qualifier("claudeVisionProvider") VisionProvider fallbackProvider) {
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
    }

    @PostConstruct
    private void init() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Runs the full two-stage pipeline.
     *
     * @param imageFiles      Optional uploaded image files (up to 3)
     * @param textDescription Optional text description of the meal
     * @param userProfile     User profile for personalised nutrition targets and medical context
     * @return Full GeminiAnalysisResult from Stage 2
     */
    public GeminiAnalysisResult analyzeWithTwoStage(
            List<MultipartFile> imageFiles,
            String textDescription,
            UserAccount userProfile) throws Exception {

        log.info("[NutritionPipeline] Starting Stage 1 — food identification");
        List<byte[]> images = new ArrayList<>();
        if (imageFiles != null) {
            for (MultipartFile file : imageFiles) {
                if (file != null && !file.isEmpty()) {
                    images.add(file.getBytes());
                }
            }
        }
        log.info("[NutritionPipeline] Stage 1 received {} image(s)", images.size());

        String textInstruction = buildStage1Prompt(textDescription);
        String stage1Json = processMealAnalysis(images, textInstruction);
        stage1Json = cleanJsonResponse(stage1Json);
        log.info("[NutritionPipeline] Stage 1 complete. Items: {}", stage1Json);

        log.info("[NutritionPipeline] Starting Stage 2 — clinical nutrition analysis");
        String stage2Prompt = buildStage2Prompt(stage1Json, userProfile);
        String stage2Json = processMealAnalysis(null, stage2Prompt);
        stage2Json = cleanJsonResponse(stage2Json);
        log.info("[NutritionPipeline] Stage 2 complete.");

        return objectMapper.readValue(stage2Json, GeminiAnalysisResult.class);
    }

    /**
     * Executes the primary provider (Gemini) with fallback to Claude on any failure.
     * Uses a default Stage 1 prompt for meal analysis.
     *
     * @param images Optional raw image byte arrays
     * @return Extracted JSON response string
     */
    public String processMealAnalysis(List<byte[]> images) {
        String defaultPrompt = buildStage1Prompt(null);
        return processMealAnalysis(images, defaultPrompt);
    }

    /**
     * Executes the primary provider (Gemini) and, if it errors or is unavailable,
     * transparently retries once with the fallback provider (Claude).
     *
     * @param images Optional raw image byte arrays (null/empty for text-only calls)
     * @param prompt Structured prompt/instructions
     * @return Extracted JSON response string
     */
    public String processMealAnalysis(List<byte[]> images, String prompt) {
        try {
            log.info("[NutritionPipeline] Invoking primary provider ({})", primaryProvider.getProviderName());
            return primaryProvider.analyzeFoodImage(images, prompt);
        } catch (Exception primaryError) {
            log.warn("[MealAnalysis] Primary provider ({}) failed: {}. Switching to fallback provider ({}).",
                    primaryProvider.getProviderName(), primaryError.getMessage(), fallbackProvider.getProviderName());
            try {
                return fallbackProvider.analyzeFoodImage(images, prompt);
            } catch (Exception fallbackError) {
                log.error("[MealAnalysis] Fallback provider ({}) also failed: {}",
                        fallbackProvider.getProviderName(), fallbackError.getMessage());
                throw fallbackError;
            }
        }
    }

    // =========================================================================
    //  STAGE 1 — VISION / EXTRACTION PROMPT
    // =========================================================================

    /**
     * Builds the Stage-1 system + user prompt block sent to the multimodal model.
     *
     * @param textDescription  Optional free-text meal description supplied by the user
     *                         (e.g., "chicken biryani with raita"). Pass an empty string
     *                         if the input is image-only.
     * @return A single prompt string that combines the system persona, extraction
     *         rules, hallucination-prevention clauses, and the required JSON schema.
     */
    private String buildStage1Prompt(String textDescription) {

        String userDescriptionBlock = (textDescription != null && !textDescription.isBlank())
                ? """
                  ## Additional Context Provided by the User
                  The user has described this meal as:
                  > "%s"
                  Use this text as a strong signal to resolve ambiguities in the image
                  (e.g., protein type, regional cuisine style, cooking method).
                  If the text contradicts what is visually impossible (e.g., "grilled fish"
                  but the image shows a clear vegetable stir-fry), flag the conflict in
                  the `extraction_notes` field rather than silently overriding the image.
                  """.formatted(textDescription)
                : "## User Description\nNo additional text description was provided. Rely solely on the image.";

        return """
                ################################################################################
                ##  SYSTEM PERSONA & MISSION
                ################################################################################
                You are a board-certified Clinical Dietitian and a USDA FoodData Central
                taxonomy expert embedded inside a medical-grade nutrition tracking system.
                Your ONLY job in this stage is INGREDIENT EXTRACTION and WEIGHT ESTIMATION.
                You do NOT calculate calories or macros here — that is done downstream.

                You must be ruthlessly accurate. A real patient's clinical health outcomes
                depend on the precision of your extraction. Overconfidence, invented data,
                or omissions are patient-safety violations.

                ################################################################################
                ##  INPUT MODALITIES
                ################################################################################
                You will receive ONE or BOTH of:
                  (A) A food photograph attached to this message.
                  (B) A text description of the meal (see below).

                Analyse BOTH inputs together. The image is primary evidence; the text
                resolves ambiguities.

                %s

                ################################################################################
                ##  MANDATORY EXTRACTION RULES
                ################################################################################

                RULE 1 — USDA NOMENCLATURE ANCHOR
                Every ingredient MUST be mapped to its closest USDA FoodData Central (FDC)
                food description string. Use the FDC "food_description" field format:
                e.g., "Rice, white, short-grain, cooked" NOT "white rice" or "cooked rice".
                Append the FDC ID as `fdc_id` if you are ≥90%% confident (set to null otherwise).

                RULE 2 — HIDDEN INGREDIENT MANDATE (ANTI-HALLUCINATION CONTRACT)
                Traditional and home-cooked dishes ALWAYS contain ingredients not visible
                to the camera. You are REQUIRED to infer and include every hidden ingredient.
                The following are NON-NEGOTIABLE additions — failure to include them when
                applicable is a critical extraction error:

                  • SOUTH INDIAN DISHES (dosa, idli, vada, sambar, rasam, upma, pongal):
                    - Fermented batter ALWAYS contains salt (≥1g per 100g batter).
                    - Tempering oil (coconut/sesame/groundnut, ~5–15 mL per serving).
                    - Mustard seeds, curry leaves, dried red chillies (tempering).
                    - Sambar: tamarind paste, sambar powder, ghee/oil, salt.
                    - Chutneys: coconut chutney has coconut oil or fresh coconut fat; add it.

                  • STIR-FRIES & SAUTÉED DISHES (any cuisine):
                    - Cooking oil (1–3 tbsp per standard serving). Estimate type from context.
                    - Salt (at minimum 0.5g per serving, typically 1–2g).

                  • CURRIES & GRAVIES (Indian, Thai, Chinese):
                    - Oil used for frying base masala (ghee/oil, 10–20 mL per serving).
                    - Salt (1–2g per serving).
                    - Onion, tomato, ginger-garlic paste if a curry base is implied.
                    - Thai curries: coconut milk (100–150 mL per serving).

                  • RICE & GRAIN DISHES:
                    - Biryani / pulao: ghee or oil (10–20 mL), whole spices, salt.
                    - Plain cooked rice still absorbs salt from cooking water if salted.

                  • BREAD & BAKED GOODS:
                    - Commercial bread: ~400–500 mg sodium per 2-slice serving.
                    - Paratha / roti: oil/ghee for cooking (3–8g per piece).

                  • SALADS:
                    - Dressing oil (if dressed): 1–2 tbsp olive/vegetable oil.
                    - Salt added during tossing.

                  • EGGS (any preparation):
                    - Butter or oil used in cooking (5–10g).
                    - Salt added during preparation.

                  General floor values (apply when you have no better estimate):
                    - Salt: minimum 0.5g per distinct dish.
                    - Cooking fat: minimum 5g per cooked/sautéed dish.
                  These floors exist because zero-sodium, zero-fat home cooking is
                  physiologically implausible.

                RULE 3 — WEIGHT ESTIMATION PROTOCOL
                Estimate gram weights using standard visual volumetric cues:
                  • Use standard serving vessel sizes as reference (katori = ~150 mL,
                    standard dinner plate = ~26 cm diameter, rice bowl = ~350 mL).
                  • For plated meals, estimate total plate fill %% and decompose.
                  • Provide a confidence_range_g [low, high] bracket (±15%% minimum width).
                  • For invisible/hidden ingredients, use recipe-standard quantities and
                    set `is_hidden: true` on that item.

                RULE 4 — UNCERTAINTY DISCIPLINE
                  • If you CANNOT identify an ingredient with ≥60%% confidence, still include
                    it with `confidence_score` set appropriately and a note in `item_notes`.
                  • Never set `estimated_weight_g` to 0 for a dish that contains fat or sodium
                    from cooking. Zero is almost always wrong.
                  • If the image is blurry, occluded, or ambiguous, document this in
                    `extraction_notes` and widen your confidence_range_g bracket.

                RULE 5 — NO DOWNSTREAM COMPUTATION
                Do NOT include calorie or macro numbers. Do NOT calculate totals.
                The downstream clinical model performs all calculations.
                Your job ends at: ingredient name, weight, and metadata.

                ################################################################################
                ##  REQUIRED OUTPUT — STRICT JSON SCHEMA
                ################################################################################
                Output ONLY the following JSON object. No markdown fences, no preamble,
                no apologies, no explanations outside the JSON fields.

                {
                  "meal_label": "<short human-readable meal name, e.g., 'South Indian Breakfast Thali'>",
                  "cuisine_type": "<e.g., 'South Indian', 'North Indian', 'Chinese', 'Mediterranean', 'Unknown'>",
                  "meal_type_guess": "<'Breakfast' | 'Lunch' | 'Dinner' | 'Snack' | 'Unknown'>",
                  "image_quality": "<'Clear' | 'Partially Occluded' | 'Blurry' | 'No Image'>",
                  "extraction_confidence": "<'High' | 'Medium' | 'Low'>",
                  "extraction_notes": "<string: document any ambiguities, conflicts between image and text, or quality issues>",
                  "ingredients": [
                    {
                      "item_id": "<sequential integer starting at 1>",
                      "usda_food_description": "<USDA FoodData Central food_description string>",
                      "fdc_id": "<integer FDC ID or null>",
                      "common_name": "<colloquial name, e.g., 'Masoor Dal'>",
                      "estimated_weight_g": <number: single best-estimate gram weight>,
                      "confidence_range_g": {
                        "low": <number>,
                        "high": <number>
                      },
                      "confidence_score": <number between 0.0 and 1.0>,
                      "is_hidden": <boolean: true if inferred, not visually confirmed>,
                      "cooking_method": "<'Raw' | 'Boiled' | 'Fried' | 'Grilled' | 'Baked' | 'Steamed' | 'Sautéed' | 'Unknown'>",
                      "item_notes": "<string: any relevant notes about this item, or null>"
                    }
                  ],
                  "total_estimated_meal_weight_g": <number: sum of all estimated_weight_g values>,
                  "hidden_ingredient_count": <integer: count of items where is_hidden is true>,
                  "pipeline_stage": "STAGE_1_EXTRACTION"
                }

                Begin extraction now.
                """.formatted(userDescriptionBlock);
    }


    // =========================================================================
    //  STAGE 2 — CLINICAL REASONING PROMPT
    // =========================================================================

    /**
     * Builds the Stage-2 system + user prompt block sent to the text-only
     * clinical reasoning model.
     *
     * @param stage1Json  The raw JSON string output from the Stage-1 model.
     * @param user        The fully-populated {@link UserAccount} entity containing
     *                    the user's physical profile, medical conditions, and goals.
     * @return A single prompt string that injects the user profile, the Stage-1
     *         JSON, all clinical rules, the math verification contract, and the
     *         required deep-assessment JSON schema.
     */
    private String buildStage2Prompt(String stage1Json, UserAccount user) {

        // Null-safe: an unauthenticated/unknown user still gets a general analysis
        // (dynamic targets fall back to clinical defaults below).
        if (user == null) {
            user = new UserAccount();
        }

        // ── 1. Resolve medical condition flags ────────────────────────────────
        boolean hasAcne          = user.getMedicalConditions() != null &&
                                   user.getMedicalConditions().stream()
                                       .anyMatch(c -> c.toLowerCase().contains("acne"));
        boolean hasDiabetes      = user.getMedicalConditions() != null &&
                                   user.getMedicalConditions().stream()
                                       .anyMatch(c -> c.toLowerCase().contains("diabet"));
        boolean hasHypertension  = user.getMedicalConditions() != null &&
                                   user.getMedicalConditions().stream()
                                       .anyMatch(c -> c.toLowerCase().contains("hypertens")
                                                   || c.toLowerCase().contains("blood pressure"));
        boolean hasPCOS          = user.getMedicalConditions() != null &&
                                   user.getMedicalConditions().stream()
                                       .anyMatch(c -> c.toLowerCase().contains("pcos")
                                                   || c.toLowerCase().contains("polycystic"));
        boolean hasIBS           = user.getMedicalConditions() != null &&
                                   user.getMedicalConditions().stream()
                                       .anyMatch(c -> c.toLowerCase().contains("ibs")
                                                   || c.toLowerCase().contains("irritable bowel"));
        boolean hasKidneyDisease = user.getMedicalConditions() != null &&
                                   user.getMedicalConditions().stream()
                                       .anyMatch(c -> c.toLowerCase().contains("kidney")
                                                   || c.toLowerCase().contains("renal")
                                                   || c.toLowerCase().contains("ckd"));

        // ── 2. Resolve user profile fields (null-safe, mapped to available model) ──
        String fullName = user.getDisplayName() != null ? user.getDisplayName() : "User";

        int age = 0;
        if (user.getPhysicalMetrics() != null && user.getPhysicalMetrics().getAge() != null) {
            age = user.getPhysicalMetrics().getAge();
        } else if (user.getAge() != null) {
            age = user.getAge();
        }

        String biologicalSex = "Not specified";
        if (user.getPhysicalMetrics() != null && user.getPhysicalMetrics().getGender() != null) {
            biologicalSex = user.getPhysicalMetrics().getGender();
        }

        double heightCm = 0.0;
        if (user.getPhysicalMetrics() != null && user.getPhysicalMetrics().getHeight() != null) {
            heightCm = user.getPhysicalMetrics().getHeight();
        } else if (user.getHeight() != null) {
            heightCm = user.getHeight();
        }

        double weightKg = 0.0;
        if (user.getPhysicalMetrics() != null && user.getPhysicalMetrics().getWeight() != null) {
            weightKg = user.getPhysicalMetrics().getWeight();
        } else if (user.getWeight() != null) {
            weightKg = user.getWeight();
        }

        double bmi            = user.getBmi() != null ? user.getBmi() : 0.0;
        String bmiCategory    = "Unknown";  // Not available in current model
        String bodyFatPct     = "Not measured";  // Not available in current model
        String activityLevel  = user.getActivityLevel() != null ? user.getActivityLevel().name() : "Unknown";

        // ── 3. Compute remaining budget (with null-safe defaults) ─────────────
        double tdee              = user.getTdee() != null ? user.getTdee() : 2000.0;
        String primaryGoal       = user.getFitnessGoal() != null ? user.getFitnessGoal().name() : "Not specified";

        double goalCalories = tdee;
        if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedCalories() != null) {
            goalCalories = user.getDynamicTargets().getCalculatedCalories();
        } else if (user.getTargetCalories() != null) {
            goalCalories = user.getTargetCalories();
        }

        double goalProteinG = 50.0;
        if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedProtein() != null) {
            goalProteinG = user.getDynamicTargets().getCalculatedProtein();
        } else if (user.getTargetProtein() != null) {
            goalProteinG = user.getTargetProtein();
        }

        double goalCarbsG = 250.0;
        if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedCarbs() != null) {
            goalCarbsG = user.getDynamicTargets().getCalculatedCarbs();
        }

        double goalFatG = 65.0;
        if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedFat() != null) {
            goalFatG = user.getDynamicTargets().getCalculatedFat();
        }

        // Fields not available in current model — use clinical defaults
        double goalSodiumMg      = 2300.0;
        double goalFiberG        = 25.0;
        double goalSugarMaxG     = 50.0;

        // Daily consumed — not tracked in current model, default to 0
        double consumedCalories  = 0.0;
        double consumedProteinG  = 0.0;
        double consumedCarbsG    = 0.0;
        double consumedFatG      = 0.0;
        double consumedSodiumMg  = 0.0;
        double consumedFiberG    = 0.0;

        // ── 4. Build medical condition context block ──────────────────────────
        boolean hasAnyCondition = user.getMedicalConditions() != null && !user.getMedicalConditions().isEmpty();
        String medicalConditionList = hasAnyCondition
                ? String.join(", ", user.getMedicalConditions())
                : "None reported";

        // Health analysis is driven by the user's OWN profile. With no conditions on
        // file we run a gentle, general-population analysis instead of assuming acne/
        // diabetes/etc. — the profile is the single source of truth here.
        String healthProfileClause = hasAnyCondition
                ? ("""
                  HEALTH PROFILE — tailor the analysis to the user's OWN conditions.
                  Active conditions from the user's profile: %s.
                  Base every clinical finding and recommendation on THESE conditions.
                  For any listed condition that has no specific protocol below, apply
                  standard evidence-based dietary guidance for it. Do NOT introduce
                  conditions the user did not report.
                """).formatted(medicalConditionList)
                : """
                  HEALTH PROFILE — no specific conditions on file.
                  The user has NOT reported any medical conditions, so run a balanced,
                  general-population analysis:
                    • Do NOT assume or invent conditions (no presumed acne, diabetes,
                      PCOS, hypertension, etc.) and do NOT apply condition-specific fear
                      framing. The health_analysis should contain only conditions with a
                      genuine, food-driven finding — otherwise return an empty analysis.
                    • Judge the meal on general nutrition quality. Highlight genuine
                      strengths; keep concerns proportionate, practical and non-alarmist.
                    • Still compute glycaemic load and flag only genuinely significant
                      issues (very high free sugar, very high sodium, trans fat). Do not
                      manufacture clinical risk the food does not warrant.
                """;

        String acneClause = hasAcne ? """

                  ⚠ ACTIVE CONDITION — ACNE / HORMONAL SKIN CONDITION DETECTED:
                  Apply the following evidence-based rules (derived from Journal of the
                  Academy of Nutrition and Dietetics, 2016; Adebamowo et al., 2005):
                    • High-glycaemic load foods (white rice, white bread, sugary drinks,
                      refined flour products): FLAG as HIGH_RISK. These foods spike insulin
                      and IGF-1, which upregulate sebum production and comedone formation.
                      Do NOT soften this finding. State the mechanistic pathway explicitly
                      in the clinical_flags array.
                    • Full-fat dairy (milk, paneer, whey): FLAG as MODERATE_RISK due to
                      IGF-1 and androgen precursor content.
                    • Omega-6 dominant oils (sunflower, corn, soybean): FLAG as MODERATE_RISK
                      (pro-inflammatory pathway via arachidonic acid).
                    • Omega-3 rich foods: FLAG as PROTECTIVE. Note the anti-inflammatory benefit.
                    • Whole, low-GI fruits and vegetables with intact fibre (apple,
                      berries, citrus, leafy greens): treat as PROTECTIVE / NEUTRAL,
                      NOT an acne trigger. Their fibre blunts the glucose→insulin→IGF-1
                      pathway. Never flag the intrinsic sugar of whole fruit as high-risk.
                    • Calculate and report the meal's estimated Glycaemic Load (GL).
                """ : "";

        String diabetesClause = hasDiabetes ? """

                  ⚠ ACTIVE CONDITION — DIABETES / INSULIN RESISTANCE DETECTED:
                  Apply ADA (American Diabetes Association) 2024 Standards of Care:
                    • Calculate estimated Glycaemic Load (GL) for the full meal.
                    • Any meal with GL > 20 must be flagged HIGH_RISK for post-prandial
                      glucose excursion.
                    • White rice, refined bread, sugary beverages, fruit juice: HIGH_RISK.
                    • Recommend specific lower-GI substitutes in recommendations array.
                    • Evaluate carbohydrate distribution: >60g net carbs per meal is
                      flagged as exceeding ADA single-meal carb guidance (45–60g target).
                """ : "";

        String hypertensionClause = hasHypertension ? """

                  ⚠ ACTIVE CONDITION — HYPERTENSION DETECTED:
                  Apply AHA (American Heart Association) dietary sodium guidelines:
                    • Target: <1500 mg sodium/day (AHA ideal) or <2300 mg (AHA acceptable).
                    • If this meal alone contributes >600 mg sodium, flag as HIGH_RISK.
                    • Identify the top sodium-contributing ingredients explicitly.
                    • Evaluate saturated fat content against AHA <7%% of daily calorie target.
                    • Recommend DASH-diet-aligned modifications in the recommendations array.
                """ : "";

        String pcosClause = hasPCOS ? """

                  ⚠ ACTIVE CONDITION — PCOS DETECTED:
                  Apply ESHRE/ASRM PCOS evidence guidelines:
                    • Insulin sensitivity is impaired. Flag all high-GI / high-GL foods
                      as HIGH_RISK (same mechanism as acne + diabetes combined).
                    • Evaluate dairy and saturated fat load (androgen upregulation risk).
                    • Flag any trans-fat-containing processed foods as HIGH_RISK.
                    • Recommend anti-inflammatory and low-GL food swaps.
                """ : "";

        String ibsClause = hasIBS ? """

                  ⚠ ACTIVE CONDITION — IBS DETECTED:
                  Apply NICE IBS dietary guidelines (2017) and Monash University FODMAP data:
                    • Identify HIGH-FODMAP ingredients (onion, garlic, wheat, lactose,
                      excess fructose, legumes) and flag each as MODERATE_RISK or HIGH_RISK.
                    • Flag high-fat meals (>20g fat) as potential IBS trigger.
                    • Evaluate fibre type: soluble fibre (oats, psyllium) = PROTECTIVE;
                      insoluble excess = potential MODERATE_RISK.
                """ : "";

        String kidneyClause = hasKidneyDisease ? """

                  ⚠ ACTIVE CONDITION — CHRONIC KIDNEY DISEASE DETECTED:
                  Apply KDOQI / NKF dietary guidelines:
                    • Protein: Flag if this meal provides >0.6g protein per kg body weight
                      in a single sitting (risk of excess BUN load).
                    • Potassium: Flag high-potassium foods (banana, potato, tomato, spinach,
                      orange) as HIGH_RISK if meal potassium estimate exceeds 500 mg.
                    • Phosphorus: Flag dairy, nuts, seeds, cola, and processed meats as
                      MODERATE_RISK to HIGH_RISK.
                    • Sodium: Apply strict <1500 mg/day ceiling; flag any excess.
                """ : "";

        // ── 5. Build glycaemic reality clause (always present) ────────────────
        String glycaemicRealityClause = """
                  GLYCAEMIC REALITY MANDATE (physiological accuracy, NOT moralisation):

                  A) REFINED / FREE-SUGAR carbohydrates — apply full scrutiny. These
                     drive a rapid glucose and insulin response and MUST be called out:
                    • White rice (GI 64–72): a 200g serving is ~52g net carbohydrate,
                      glycaemic load ~33 — a significant insulin response.
                    • White bread, maida (all-purpose flour) products: GI 70–85.
                    • Refined / sugar-added breakfast cereals and instant oats: GI 70+.
                    • Sugar, honey, jaggery, syrups, and sugar-sweetened drinks.
                    • Fruit JUICE (even 100%% natural): fibre is removed, so its sugar
                      behaves like free sugar — score it as free sugar.
                    For these, state the mechanistic pathway: rapid glucose absorption →
                    insulin spike → downstream hormonal and metabolic consequences.

                  B) WHOLE-FOOD CARVE-OUT — equally mandatory; do NOT over-flag:
                    Whole fruits and vegetables, legumes, nuts/seeds and intact whole
                    grains carry their sugar INSIDE an intact fibre matrix, which blunts
                    the glycaemic response. Their intrinsic sugar is NOT free sugar.
                    • Do NOT flag whole fruit/veg as HIGH_SUGAR. Use INTRINSIC_SUGAR
                      (informational) and let fibre + true GL reflect the real impact.
                    • Do NOT describe a whole apple, banana or bowl of dal as an
                      "insulin spike" — their glycaemic load is modest and their fibre,
                      vitamins and polyphenols are protective.
                    • `added_sugar_g` counts FREE sugar ONLY (added/refined sugar plus
                      juice sugar). It must NEVER include the intrinsic sugar of whole
                      fruit, vegetables or plain dairy.
                    • Never stack glycaemic + sugar + "empty calorie" penalties on a
                      whole food. Scrutinise refined carbs and free sugar; protect
                      genuine whole foods. This applies to ALL users.
                """;

        // ── 5b. Build context-aware scoring rubric (always present) ───────────
        // Plain-text block (not run through String.format) so raw % signs are fine.
        String scoringRubricClause = """
                  Compute meal_score.overall_score (0–100) and letter_grade with the
                  CONTEXT-AWARE rubric below. FIRST decide meal_context:

                    • "main"  → meal_type ∈ {Breakfast, Lunch, Dinner, Post Workout}
                                OR this meal is ≥ 25% of the daily calorie target.
                    • "light" → everything else (Snack, Mid-Morning, Midnight).

                  A snack is not a failed dinner. Judge every meal by its own job:
                  protein is the BACKBONE of a main meal but only a BONUS for a light
                  snack. Never penalise a food for a role it was never meant to play.

                  WHOLE-FOOD GRADE FLOORS (compute the raw banded score, then take the
                  HIGHER of the raw score and any floor that applies):
                    • A meal made ENTIRELY of whole / minimally-processed foods (NOVA 1)
                      — fresh fruit, veg, legumes, plain nuts, plain dairy, intact whole
                      grains — with free sugar ≤ 5g, sodium < 400mg and saturated fat
                      < 3g scores AT LEAST 85 (grade A) if light, or AT LEAST 70
                      (grade B) if main. A single whole fruit is an A-grade snack.
                    • A meal that is ≥ 75% whole-food by calories, with free sugar ≤ 12g,
                      sodium < 500mg and saturated fat < 5g scores AT LEAST 60 (grade C).
                      Whole food is never graded "poor".
                    • Floors NEVER apply to ultra-processed foods, deep-fried foods,
                      added-sugar products, or refined-grain-dominant meals.

                  MAIN meals (per-meal protein target ≈ daily protein target ÷ 4):
                   1. Protein (30): ≥35g→30 · 25–34→22 · 15–24→14 · 10–14→8 · 5–9→4 · <5→1
                   2. Glycaemic (25): meal GL<10→23–25 · 10–14→17–20 · 15–19→10–14 ·
                      20–29→4–8 · ≥30→1–3. Score glycaemic load from FREE sugar /
                      refined carbs harshly; whole-fruit GL is treated gently.
                   3. Macro balance (20): protein ≥25% kcal, fat 20–35%, carbs 35–55%:
                      all in→20 · 1 out→14 · 2 out→8 · 3 out→3
                   4. Fibre & micros (15): ≥6g→12–15 · 4–5.9→9–11 · 2–3.9→6–8 ·
                      1–1.9→3–5 · <1→1–2 (+≤3 bonus for micronutrient diversity /
                      anti-inflammatory spices / fermented foods; cap 15)
                   5. Sodium & sat fat (10): <400mg AND <3g→10 · one of the two→7 ·
                      400–700 AND 3–5g→4 · >700 OR >5g→1–2

                  LIGHT meals (snacks judged as snacks — protein is a bonus, not backbone):
                   1. Food quality (30): all whole / minimally processed→26–30 ·
                      mostly whole→18–25 · mixed→10–17 · mostly ultra-processed→1–9
                   2. Glycaemic (25): same bands as MAIN
                   3. Fibre & micros (20): ≥5g→17–20 · 3–4.9→12–16 · 1.5–2.9→7–11 ·
                      <1.5→1–6 (+bonus as above; cap 20)
                   4. Protein contribution (15): ≥15g→15 · 8–14.9→11 · 4–7.9→7 ·
                      1–3.9→4 · <1→1
                   5. Sodium & sat fat (10): same as MAIN

                  Grades: A 85–100 (excellent) · B 70–84 (good) · C 55–69 (fair) ·
                  D 40–54 (poor) · F <40 (very poor). Clamp 0–100. letter_grade MUST
                  match overall_score to these bands. meal_score.score_rationale MUST
                  name the context ("As a snack, …" / "As a main meal, …") and MUST
                  cite genuine strengths, not only faults.

                  CALIBRATION ANCHORS (do not output):
                    • "1 apple, Snack" → light, entirely whole food, free sugar 0 →
                      whole-food floor → ~90, grade A. Fruit is a good snack.
                    • "Apple + 240ml apple juice, Snack" → light; the juice is free
                      sugar (fibre removed) so the 75%-whole floor is NOT met, but the
                      whole apple keeps it out of "poor": land ~C (fair), with the juice
                      named as the single lever to improve.
                    • "Lemon rice, Lunch" (200g white rice, 20g peanuts) → main;
                      protein ~10g, GL high, no floor → ~35, grade F. A protein-free
                      high-GL lunch is not a good recomposition meal.
                """;

        return """
                ################################################################################
                ##  SYSTEM PERSONA & MISSION
                ################################################################################
                You are a board-certified Clinical Dietitian, Endocrinology Nutrition
                Specialist, and Epidemiologist with 20 years of evidence-based dietary
                counselling experience. You are operating as a real-time clinical decision
                support engine inside a medical-grade Life OS.

                Your output will be read by:
                  (a) The patient themselves, to understand what they just ate and what to do next.
                  (b) Potentially, their physician or registered dietitian as supporting data.

                This means:
                  • Your math MUST be correct. Errors in calorie/macro totals are
                    patient-safety violations.
                  • Your clinical flags MUST be grounded in WHO, AHA, and ADA guidelines.
                    Do not invent warnings. Do not suppress real warnings.
                  • Your recommendations MUST be specific, actionable, and evidence-cited.
                    "Eat healthier" is not a recommendation. "Replace 150g white rice with
                    150g cooked quinoa to reduce glycaemic load from 33 to 14" IS.
                  • You MUST NOT hallucinate nutritional values. Use your training knowledge
                    of standard USDA FoodData Central nutritional profiles per 100g for
                    every ingredient. If you are uncertain about a value, use a conservative
                    estimate and document it in the _reasoning_scratchpad.

                ################################################################################
                ##  USER HEALTH PROFILE  (Ground Truth — Do NOT alter or ignore)
                ################################################################################
                These values are authoritative. Do not override them. Do not fabricate goals.

                  Personal:
                    Name             : %s
                    Age              : %d years
                    Biological Sex   : %s
                    Height           : %.1f cm
                    Weight           : %.1f kg
                    BMI              : %.1f  (%s)
                    Body Fat %%       : %s
                    Activity Level   : %s

                  Metabolic:
                    TDEE             : %.0f kcal/day  (Total Daily Energy Expenditure)
                    Goal             : %s

                  Daily Nutrition Targets (system-calculated, user-specific):
                    Calories         : %.0f kcal/day
                    Protein          : %.1f g/day
                    Carbohydrates    : %.1f g/day
                    Fat              : %.1f g/day
                    Sodium           : %.0f mg/day
                    Dietary Fibre    : %.1f g/day
                    Max Added Sugar  : %.1f g/day

                  Already Consumed Today (BEFORE this meal — from daily log):
                    Calories         : %.0f kcal
                    Protein          : %.1f g
                    Carbohydrates    : %.1f g
                    Fat              : %.1f g
                    Sodium           : %.0f mg
                    Dietary Fibre    : %.1f g

                  Remaining Budget AFTER today's prior meals (calculated for you):
                    Calories         : %.0f kcal
                    Protein          : %.1f g
                    Carbohydrates    : %.1f g
                    Fat              : %.1f g
                    Sodium           : %.0f mg

                  Active Medical Conditions : %s
                  Current Medications       : %s
                  Dietary Restrictions      : %s
                  Primary Nutrition Goal    : %s

                ################################################################################
                ##  HEALTH PROFILE & CONDITION PROTOCOLS  (tailored to the user's profile)
                ################################################################################
                %s
                %s
                %s
                %s
                %s
                %s
                %s

                ################################################################################
                ##  GLYCAEMIC REALITY MANDATE  (Always Active)
                ################################################################################
                %s

                ################################################################################
                ##  MEAL SCORING RUBRIC  (Always Active — governs meal_score)
                ################################################################################
                %s

                ################################################################################
                ##  STAGE-1 EXTRACTION INPUT
                ################################################################################
                The following JSON was produced by the multimodal Vision stage.
                It contains the identified ingredients with estimated gram weights.
                Use USDA FoodData Central nutritional profiles (per 100g) to compute
                macros for each ingredient based on its estimated_weight_g.

                --- BEGIN STAGE-1 JSON ---
                %s
                --- END STAGE-1 JSON ---

                ################################################################################
                ##  MANDATORY COMPUTATION RULES
                ################################################################################

                MATH CONTRACT — STRICT ENFORCEMENT:
                  The following calorie conversion factors are absolute law in this system.
                  You MUST use ONLY these values. Do not approximate differently.

                    1 gram of Protein      = 4.0 kcal  (Atwater factor)
                    1 gram of Carbohydrate = 4.0 kcal  (Atwater factor)
                    1 gram of Fat          = 9.0 kcal  (Atwater factor)
                    1 gram of Alcohol      = 7.0 kcal  (if applicable)
                    1 gram of Dietary Fibre = 2.0 kcal (FDA soluble average; use only
                                                        if your nutrient source subtracts
                                                        fibre from total carbs)

                  SODIUM CALCULATION LAW:
                    If you identify 'Salt' (or 'table salt', or any variations of salt used as a seasoning)
                    as an ingredient in the Stage-1 JSON or infer it as a hidden ingredient, you MUST calculate
                    its sodium content using the clinical standard:
                      1 gram of table salt = approximately 400 mg of sodium (400 mg/g).
                    Never list the sodium content of raw salt as 0 mg.

                  VERIFICATION GATE — YOU MUST PASS THIS CHECK:
                    total_calories MUST equal:
                    (total_protein_g × 4.0) + (total_carbs_g × 4.0) + (total_fat_g × 9.0)
                    Tolerance: ±2 kcal (rounding only).
                    If your computed total does not pass this gate, recompute before output.

                  ITEM-LEVEL MATH:
                    For each ingredient, the per-item calories_kcal MUST equal:
                    (protein_g × 4.0) + (carbs_g × 4.0) + (fat_g × 9.0)
                    The sum of all per-item calories_kcal MUST equal total_calories_kcal.

                  NEVER set any macro or calorie field to a value that fails the above.

                CHAIN-OF-THOUGHT REQUIREMENT:
                  Before populating any numeric fields, you MUST write out your full
                  clinical reasoning in the `_reasoning_scratchpad` field (see schema).
                  This scratchpad must contain, IN ORDER:
                    [Step 1 — Ingredient Nutrient Lookup]
                      For each ingredient: state the USDA per-100g values you are using
                      (protein, carbs, fat, sodium, fibre) and show the scaled calculation
                      for the estimated_weight_g. Example:
                      "White rice, cooked: per 100g → P:2.7g, C:28.6g, F:0.3g, Na:1mg.
                       At 200g → P:5.4g, C:57.2g, F:0.6g, Na:2mg, Cals: 5.4×4 + 57.2×4 + 0.6×9 = 254 kcal"
                      *Sodium Law check*: If the ingredient is 'Salt', use 400mg sodium per 1g of salt.
                      Example: "Salt: 2g → Na: 2 × 400 = 800mg. Calories: 0 kcal"

                    [Step 2 — Totals Aggregation]
                      Sum all per-item values. Show the running total.

                    [Step 3 — Math Verification Gate]
                      Verify: (P×4) + (C×4) + (F×9) = stated total calories.
                      If it fails, show the correction. State: "GATE: PASSED" or "GATE: FAILED → corrected to X".

                    [Step 4 — Budget Analysis]
                      Compare meal totals to remaining daily budget.
                      Flag over-budget macros.

                    [Step 5 — Clinical Flag Reasoning]
                      For each active medical condition, walk through why specific
                      ingredients trigger the flags you are about to set.

                    [Step 6 — Glycaemic Load Calculation]
                      For each high-GI ingredient: GL = (GI × net_carbs_g) / 100.
                      Sum for total meal GL. Classify: Low (<10), Medium (10–19), High (≥20).
                      Separate FREE-sugar / refined-carb GL from whole-fruit GL.

                    [Step 7 — Contextual Scoring]
                      State meal_context (main vs light) and WHY. Walk the MEAL SCORING
                      RUBRIC band by band, show each component score, sum them, then apply
                      any whole-food floor (take the higher value). State the final
                      overall_score and the matching letter_grade band.

                ################################################################################
                ##  REQUIRED OUTPUT — STRICT JSON SCHEMA
                ################################################################################
                Output ONLY the following JSON object.
                No markdown fences. No preamble. No text before the opening brace.
                The `_reasoning_scratchpad` MUST be the very first key in the object.

                {
                  "_reasoning_scratchpad": "<REQUIRED FIRST FIELD. Write your full Step 1–6 chain-of-thought here as a single string. This is your working memory and will be stored for clinical audit. Be thorough. Do not truncate.>",

                  "pipeline_stage": "STAGE_2_CLINICAL_ASSESSMENT",
                  "meal_label": "<from Stage-1 output>",
                  "cuisine_type": "<from Stage-1 output>",
                  "meal_type": "<Breakfast | Lunch | Dinner | Snack>",
                  "analysis_timestamp_utc": "<ISO-8601 UTC timestamp string — use the current time>",

                  "macro_totals": {
                    "calories_kcal": <number: total meal calories — MUST pass math gate>,
                    "protein_g": <number>,
                    "carbohydrates_g": <number>,
                    "fat_g": <number>,
                    "saturated_fat_g": <number>,
                    "unsaturated_fat_g": <number>,
                    "trans_fat_g": <number: 0.0 if none detected>,
                    "dietary_fiber_g": <number>,
                    "sugar_g": <number: total sugars including naturally occurring>,
                    "added_sugar_g": <number: estimated added/refined sugar only>,
                    "sodium_mg": <number>,
                    "potassium_mg": <number: estimate from USDA data>,
                    "cholesterol_mg": <number: estimate from USDA data>,
                    "math_verification": {
                      "expected_calories_from_macros": <number: (protein_g×4) + (carbs_g×4) + (fat_g×9)>,
                      "stated_calories": <number: same as calories_kcal above>,
                      "delta_kcal": <number: stated_calories minus expected_calories_from_macros>,
                      "gate_passed": <boolean: true if |delta_kcal| ≤ 2>
                    }
                  },

                  "ingredients_breakdown": [
                    {
                      "item_id": <integer: matching Stage-1 item_id>,
                      "name": "<from Stage-1 usda_food_description>",
                      "common_name": "<from Stage-1 common_name>",
                      "estimated_weight_g": <number: from Stage-1>,
                      "is_hidden": <boolean: from Stage-1>,
                      "nutrition_per_100g_source": "<e.g., 'USDA FDC SR Legacy' or 'USDA FDC Foundation'>",
                      "nutrients": {
                        "calories_kcal": <number: for this item at estimated_weight_g>,
                        "protein_g": <number>,
                        "carbohydrates_g": <number>,
                        "fat_g": <number>,
                        "saturated_fat_g": <number>,
                        "dietary_fiber_g": <number>,
                        "sugar_g": <number>,
                        "sodium_mg": <number>
                      },
                      "item_math_check": {
                        "expected_kcal": <number: (protein_g×4)+(carbs_g×4)+(fat_g×9)>,
                        "stated_kcal": <number: same as nutrients.calories_kcal>,
                        "passed": <boolean>
                      },
                      "glycaemic_index_estimate": <number or null: GI value from established tables>,
                      "glycaemic_load_contribution": <number or null: (GI × net_carbs_g) / 100>,
                      "clinical_item_flags": ["<SHORT_CODE: reason string>"]
                    }
                  ],

                  "glycaemic_assessment": {
                    "total_meal_glycaemic_load": <number>,
                    "gl_classification": "<'Low (GL<10)' | 'Medium (GL 10-19)' | 'High (GL≥20)'>",
                    "insulin_impact_summary": "<string: mechanistic explanation of post-prandial glucose and insulin response for this specific meal>",
                    "highest_gi_offenders": [
                      {
                        "ingredient_name": "<string>",
                        "gi_estimate": <number>,
                        "gl_contribution": <number>,
                        "clinical_note": "<string: specific impact for this user>"
                      }
                    ]
                  },

                  "daily_budget_analysis": {
                    "remaining_budget_before_this_meal": {
                      "calories_kcal": %.0f,
                      "protein_g": %.1f,
                      "carbs_g": %.1f,
                      "fat_g": %.1f,
                      "sodium_mg": %.0f
                    },
                    "remaining_budget_after_this_meal": {
                      "calories_kcal": <number: remaining_before minus meal calories>,
                      "protein_g": <number>,
                      "carbs_g": <number>,
                      "fat_g": <number>,
                      "sodium_mg": <number>
                    },
                    "budget_status": {
                      "calories": "<'Under Budget' | 'Near Limit (>80%%)' | 'Over Budget'>",
                      "protein": "<'Under Budget' | 'Near Limit (>80%%)' | 'Over Budget'>",
                      "carbs": "<'Under Budget' | 'Near Limit (>80%%)' | 'Over Budget'>",
                      "fat": "<'Under Budget' | 'Near Limit (>80%%)' | 'Over Budget'>",
                      "sodium": "<'Under Budget' | 'Near Limit (>80%%)' | 'Over Budget'>"
                    },
                    "percentage_of_daily_goals_this_meal": {
                      "calories_pct": <number: (meal_calories / goal_calories) × 100>,
                      "protein_pct": <number>,
                      "carbs_pct": <number>,
                      "fat_pct": <number>,
                      "sodium_pct": <number>
                    }
                  },

                  "clinical_flags": [
                    {
                      "flag_id": "<sequential e.g., 'FLAG_001'>",
                      "severity": "<'LOW' | 'MODERATE' | 'HIGH' | 'CRITICAL'>",
                      "category": "<'GLYCAEMIC' | 'CARDIOVASCULAR' | 'RENAL' | 'INFLAMMATORY' | 'HORMONAL' | 'DIGESTIVE' | 'MACRO_IMBALANCE' | 'MICRONUTRIENT' | 'ALLERGEN' | 'GENERAL'>",
                      "condition_link": "<medical condition this flag relates to, or 'General Population'>",
                      "title": "<short flag title, e.g., 'High Glycaemic Load — Insulin Spike Risk'>",
                      "evidence_basis": "<e.g., 'ADA Standards of Care 2024, Section 5' or 'WHO Global Action Plan on NCDs'>",
                      "mechanistic_pathway": "<string: explain WHY this is a problem physiologically — do not omit this field>",
                      "affected_ingredients": ["<ingredient common_name>"],
                      "quantified_risk": "<string: e.g., 'This meal contributes 43g added sugar, which is 86%% of the AHA daily limit of 50g'>",
                      "urgency": "<'Monitor' | 'Reduce' | 'Avoid' | 'Consult Physician'>"
                    }
                  ],

                  "meal_score": {
                    "meal_context": "<'main' | 'light' — per the MEAL SCORING RUBRIC>",
                    "overall_score": <integer 0–100: from the context-aware rubric, AFTER applying whole-food floors>,
                    "score_rationale": "<string: MUST name the context ('As a snack, …' / 'As a main meal, …'), cite genuine strengths, and explain what raised and lowered the score>",
                    "macro_balance_score": <integer 0–100>,
                    "glycaemic_score": <integer 0–100: 100=low GL, 0=very high GL. Whole-fruit GL is scored gently — do not tank this for intrinsic fruit sugar>,
                    "micronutrient_density_score": <integer 0–100>,
                    "condition_safety_score": <integer 0–100: 100=no condition flags, decreases per active flag>,
                    "letter_grade": "<'A' | 'B' | 'C' | 'D' | 'F' — MUST match overall_score to the rubric bands>"
                  },

                  "recommendations": [
                    {
                      "rec_id": "<sequential e.g., 'REC_001'>",
                      "priority": "<'Critical' | 'High' | 'Medium' | 'Low'>",
                      "type": "<'Substitute' | 'Reduce_Portion' | 'Add_Ingredient' | 'Remove_Ingredient' | 'Timing' | 'Hydration' | 'Next_Meal_Guidance'>",
                      "title": "<short title>",
                      "action": "<specific, quantified action — never generic>",
                      "rationale": "<WHY — cite guideline or mechanism>",
                      "example": "<concrete example: e.g., 'Replace 150g white rice with 150g cooked red rice to reduce GL from 33 to 19'>",
                      "condition_targeted": "<medical condition this addresses, or 'General Wellness'>"
                    }
                  ],

                  "positive_highlights": [
                    {
                      "highlight_id": "<sequential e.g., 'POS_001'>",
                      "ingredient_or_aspect": "<string>",
                      "benefit": "<string: specific nutritional or clinical benefit>",
                      "evidence": "<brief citation or mechanism>"
                    }
                  ],

                  "next_meal_guidance": {
                    "suggested_calorie_range_kcal": "<string: e.g., '400–500 kcal'>",
                    "priority_nutrients_to_target": ["<e.g., 'Protein: aim for 25–30g'>", "<e.g., 'Fibre: aim for 8g'>"],
                    "foods_to_favour": ["<specific food suggestions>"],
                    "foods_to_limit": ["<specific foods to avoid or reduce at next meal, given today's log so far>"],
                    "timing_recommendation": "<string: e.g., 'Given the high GL of this meal, allow 3–4 hours before next meal to avoid compounding insulin response'>",
                    "hydration_note": "<string: e.g., 'High sodium intake in this meal — aim for 500 mL water in the next hour'>"
                  },

                  "data_quality_flags": [
                    "<string: any concerns about Stage-1 data quality that may affect accuracy of this analysis>"
                  ],

                  "disclaimer": "This analysis is generated by an AI system trained on nutritional science literature and USDA data. It is intended as a decision-support tool and does not constitute personalised medical advice. Consult a Registered Dietitian or your physician before making significant dietary changes, especially with active medical conditions."
                }

                Begin clinical analysis now. Remember: `_reasoning_scratchpad` is the FIRST key. Math gate must PASS before you write numeric fields.
                """.formatted(
                        // User profile block
                        fullName,
                        age,
                        biologicalSex,
                        heightCm,
                        weightKg,
                        bmi,
                        bmiCategory,
                        bodyFatPct,
                        activityLevel,
                        tdee,
                        primaryGoal,
                        goalCalories, goalProteinG, goalCarbsG, goalFatG, goalSodiumMg, goalFiberG, goalSugarMaxG,
                        consumedCalories, consumedProteinG, consumedCarbsG, consumedFatG, consumedSodiumMg, consumedFiberG,
                        // Remaining budgets (computed inline)
                        goalCalories  - consumedCalories,
                        goalProteinG  - consumedProteinG,
                        goalCarbsG    - consumedCarbsG,
                        goalFatG      - consumedFatG,
                        goalSodiumMg  - consumedSodiumMg,
                        // Medical context
                        medicalConditionList,
                        "None reported",  // currentMedications — not available in current model
                        "None reported",  // dietaryRestrictions — not available in current model
                        primaryGoal,
                        // Health profile (dynamic) + condition-specific clauses
                        healthProfileClause,
                        acneClause,
                        diabetesClause,
                        hypertensionClause,
                        pcosClause,
                        ibsClause,
                        kidneyClause,
                        // Glycaemic reality clause (always present)
                        glycaemicRealityClause,
                        // Context-aware scoring rubric (always present)
                        scoringRubricClause,
                        // Stage-1 JSON
                        stage1Json,
                        // Budget echo into JSON schema (pre-filled for model convenience)
                        goalCalories  - consumedCalories,
                        goalProteinG  - consumedProteinG,
                        goalCarbsG    - consumedCarbsG,
                        goalFatG      - consumedFatG,
                        goalSodiumMg  - consumedSodiumMg
                );
    }

    private String cleanJsonResponse(String rawResponse) {
        if (rawResponse == null) return "{}";
        String cleaned = rawResponse.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }
}
