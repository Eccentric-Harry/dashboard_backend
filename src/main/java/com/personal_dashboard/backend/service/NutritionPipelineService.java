package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal_dashboard.backend.dto.GeminiAnalysisResult;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.service.NutrientResolver.ExtractedItem;
import com.personal_dashboard.backend.service.NutrientResolver.ResolvedItem;
import com.personal_dashboard.backend.service.NutrientResolver.ResolvedMeal;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * NutritionPipelineService — three-step meal analysis.
 *
 * <pre>
 *   STAGE 1  vision      identify foods, portion them as count x size band
 *      |     (LLM)       -> {name, grams, confidence}. Emits NO nutrient numbers.
 *      v
 *   RESOLVE  Java        NutrientResolver against the bundled composition table.
 *      |     (no LLM)    Deterministic. Same extraction -> same macros, always.
 *      v
 *   STAGE 2  judgement   scoring, clinical flags, recommendations. Receives the
 *            (LLM)       final numbers as fact; is forbidden from recomputing them.
 * </pre>
 *
 * <p><b>Why this shape.</b> Stage 2 previously recalled "USDA per-100 g values" from model
 * memory and did the arithmetic itself, which made every macro in the app an unverifiable
 * guess. Moving the arithmetic into {@link NutrientResolver} removes that error source and
 * pays for itself twice: Stage 2 no longer emits a chain-of-thought scratchpad or a
 * per-ingredient nutrient breakdown, which were the bulk of its output tokens.
 *
 * <p><b>Prompt layout is load-bearing for cost.</b> Gemini's implicit caching is a prefix
 * match, so each prompt is assembled strictly as {@code STATIC_BLOCK + volatile tail}. The
 * static halves are compile-time constants and never interpolate anything; the user's
 * description, profile, and resolved facts all go last. Interpolating a value early — as
 * the previous implementation did with the user profile — makes every token after it
 * uncacheable.
 */
@Service
@Slf4j
public class NutritionPipelineService {

    private final VisionProvider primaryProvider;
    private final VisionProvider fallbackProvider;
    private final FoodReferenceService foodReference;
    private final NutrientResolver nutrientResolver;
    private ObjectMapper objectMapper;

    public NutritionPipelineService(
            @Qualifier("geminiVisionProvider") VisionProvider primaryProvider,
            @Qualifier("claudeVisionProvider") VisionProvider fallbackProvider,
            FoodReferenceService foodReference,
            NutrientResolver nutrientResolver) {
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
        this.foodReference = foodReference;
        this.nutrientResolver = nutrientResolver;
    }

    @PostConstruct
    private void init() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Runs the full pipeline.
     *
     * @param imageFiles      up to 3 images of the same meal (multiple angles preferred)
     * @param textDescription optional free text from the user
     * @param userProfile     may be null; Stage 2 then runs neutral clinical defaults
     */
    public GeminiAnalysisResult analyzeWithTwoStage(
            List<MultipartFile> imageFiles,
            String textDescription,
            UserAccount userProfile) throws Exception {

        List<byte[]> images = new ArrayList<>();
        if (imageFiles != null) {
            for (MultipartFile file : imageFiles) {
                if (file != null && !file.isEmpty()) images.add(file.getBytes());
            }
        }

        // ── Stage 1: vision extraction ───────────────────────────────────────
        log.info("[NutritionPipeline] Stage 1 — extraction ({} image(s))", images.size());
        String stage1Json = cleanJsonResponse(
                processMealAnalysis(images, buildStage1Prompt(textDescription)));
        List<ExtractedItem> extracted = parseExtraction(stage1Json);
        log.info("[NutritionPipeline] Stage 1 extracted {} item(s)", extracted.size());

        // ── Resolve: deterministic, no model involved ────────────────────────
        ResolvedMeal resolved = nutrientResolver.resolve(extracted);
        log.info("[NutritionPipeline] Resolved {}/{} items ({}% coverage), {} kcal, GL {}",
                resolved.getItems().size(), extracted.size(),
                Math.round(resolved.coverage() * 100),
                Math.round(resolved.total("kcal")), resolved.getGlycemicLoad());
        if (!resolved.getUnresolved().isEmpty()) {
            log.warn("[NutritionPipeline] No table entry for: {}", resolved.getUnresolved());
        }
        if (!resolved.isAtwaterConsistent()) {
            log.warn("[NutritionPipeline] Atwater check off by {} kcal — suspect table row or match",
                    resolved.getAtwaterDeltaKcal());
        }

        // ── Stage 2: judgement only ──────────────────────────────────────────
        log.info("[NutritionPipeline] Stage 2 — assessment");
        String stage2Json = cleanJsonResponse(
                processMealAnalysis(null, buildStage2Prompt(stage1Json, resolved, userProfile)));

        // Java owns the numbers; the model only contributed judgement.
        return mergeResult(stage2Json, stage1Json, resolved);
    }

    public String processMealAnalysis(List<byte[]> images) {
        return processMealAnalysis(images, buildStage1Prompt(null));
    }

    /**
     * Calls the primary provider, falling back only if a usable fallback is configured.
     *
     * <p>An unconfigured fallback used to be attempted anyway, so a primary failure became
     * a confusing authentication error from the secondary instead of the real cause.
     */
    public String processMealAnalysis(List<byte[]> images, String prompt) {
        try {
            return primaryProvider.analyzeFoodImage(images, prompt);
        } catch (Exception primaryError) {
            if (!fallbackProvider.isConfigured()) {
                log.error("[MealAnalysis] Primary ({}) failed and fallback ({}) is not configured: {}",
                        primaryProvider.getProviderName(), fallbackProvider.getProviderName(),
                        primaryError.getMessage());
                throw primaryError instanceof RuntimeException re
                        ? re : new RuntimeException(primaryError);
            }
            log.warn("[MealAnalysis] Primary ({}) failed: {}. Trying fallback ({}).",
                    primaryProvider.getProviderName(), primaryError.getMessage(),
                    fallbackProvider.getProviderName());
            return fallbackProvider.analyzeFoodImage(images, prompt);
        }
    }

    // =========================================================================
    //  STAGE 1 — extraction
    // =========================================================================

    /**
     * Static half of the Stage-1 prompt. A compile-time constant with no interpolation, so
     * it is byte-identical on every request and can serve as a cacheable prefix.
     *
     * <p>The portion ladder is the substantive change from free-form gram estimates.
     * Choosing "standard" from three labelled options is a far better-calibrated model
     * operation than producing "168", and it mirrors how food is actually served.
     */
    private static final String STAGE1_STATIC = """
            You are a dietitian's extraction assistant. Identify what is on the plate and
            how much of it there is. You do NOT calculate calories or macros — a database
            does that downstream. Emit food names and gram weights only.

            ## STEP 1 — SCALE REFERENCE (do this first)
            Name a reference object before estimating any portion: dinner plate (26 cm),
            katori/small bowl (7.5 cm rim, 150 ml), teaspoon (12 cm), tablespoon,
            fork, hand, or phone. Record it in `scale_reference`.
            If nothing usable is visible, set `scale_reference` to "none" and widen every
            confidence range by 50%. Do not pretend to a precision the image cannot support.

            ## STEP 2 — IDENTIFY EVERY COMPONENT
            Name each component using COMMON food names ("sambar", "idli", "white rice",
            "coconut chutney"). Prefer the name of the composed dish over its ingredients:
            emit "sambar", not "toor dal + tamarind + oil". The lookup table has dish-level
            entries and decomposing them produces worse matches, not better ones.

            Be exhaustive about what is ON the plate. A missed component is a missed meal
            — it contributes nothing downstream. Garnishes, sauces, dressings, pickles,
            papad, a side of curd, a spoon of chutney: each is its own item. Work across
            the plate systematically rather than naming only the dominant food. State
            the preparation where it changes the food materially (cooked, fried, boiled,
            grilled, roasted, raw) — a raw and a cooked vegetable differ by a third or
            more in energy density once water is driven off.

            ## STEP 2b — HIDDEN COMPONENTS (required)
            Home and restaurant cooking always contains things the camera cannot see.
            Include them, marked `"hidden": true`:
              - SOUTH INDIAN (dosa, idli, vada, sambar, rasam, upma, pongal): tempering oil
                (5-15 ml), salt in the batter and the dish, mustard seeds and curry leaves.
              - STIR-FRIES AND SAUTES, any cuisine: cooking oil (1-3 tsp per serving), salt.
              - CURRIES AND GRAVIES: oil or ghee for the masala base (10-20 ml), salt, and
                the onion/tomato/ginger-garlic base if a gravy is implied. Thai curries:
                coconut milk (100-150 ml).
              - RICE DISHES: ghee or oil in biryani and pulao (10-20 ml), whole spices.
              - BREADS: oil or ghee on paratha and roti (3-8 g per piece).
              - SALADS: dressing oil if dressed (1-2 tbsp), salt.
              - EGGS: the butter or oil they were cooked in (5-10 g).
            Floors when you have no better estimate: salt at least 0.5 g per savoury dish,
            cooking fat at least 5 g per cooked or sauteed dish. Zero-fat, zero-sodium
            cooking is physiologically implausible and produces badly wrong totals.

            Do not invent components you have no evidence for. An over-long list is as
            wrong as a short one — the test is whether a cook making this dish would have
            used it, not whether it is conceivable.

            ## STEP 3 — PORTION (count x size band, not free-form grams)
            For countable items give a count and a size band. Reference sizes:
              idli      small 40 g  | standard 55 g  | large 70 g
              dosa      small 90 g  | standard 120 g | large 160 g
              chapati   6in 40 g    | 7in 55 g       | 8in 75 g
              vada      standard 45 g
              banana    small 90 g  | medium 120 g   | large 150 g
              egg       large 50 g
            For volume items give vessel count and fill level:
              cooked rice  1 katori level 130 g | 1 katori heaped 175 g | plate 250 g
              sambar/dal   1 ladle 80 g | 1 katori 160 g
              curd         1 katori 140 g
              chutney      1 tbsp 18 g | 2 tbsp 36 g
              oil/ghee     1 tsp 5 g | 1 tbsp 14 g
            For anything NOT on that ladder, fall back to volumetric estimation against
            your scale reference:
              - standard dinner plate 26 cm; katori/small bowl 150 ml; rice bowl 350 ml;
                teaspoon 5 ml; tablespoon 15 ml; standard glass 200 ml
              - estimate what fraction of the plate or bowl the item fills, convert that
                to volume, then to grams using the food's density: watery stews ~1.0 g/ml,
                cooked grains ~0.8 g/ml, leafy salads ~0.3 g/ml, oils ~0.9 g/ml
              - for meat and fish, thickness matters as much as area; a palm-sized fillet
                is roughly 100-120 g

            Always convert to total grams in `grams` — 3 standard idlis is 165, not 3.
            Put the reasoning in `portion_basis` ("3 idlis x 55 g standard", or
            "fills ~40% of a 26 cm plate, ~1.5 cm deep, cooked grain ~ 180 g").
            Give `grams_low` and `grams_high` as a genuine bracket, at least +/-15%.

            ## STEP 4 — CONFIDENCE
            `confidence` is 0.0-1.0 and must be honest. Below 0.7 signals the UI to ask the
            user. A confident wrong answer is worse than an admitted uncertain one.

            ## OUTPUT — raw JSON only, no markdown fence, no text outside the object
            {
              "meal_label": "<short dish name, 2-5 words>",
              "cuisine_type": "<e.g. South Indian, North Indian, Continental, Unknown>",
              "meal_type_guess": "<Breakfast|Lunch|Dinner|Snack|Unknown>",
              "image_quality": "<Clear|Partially Occluded|Blurry|No Image>",
              "scale_reference": "<object used, or 'none'>",
              "extraction_notes": "<ambiguities, conflicts with the description, quality issues>",
              "items": [
                {
                  "name": "<common food name>",
                  "grams": <number: total grams for this component>,
                  "portion_basis": "<how you got there>",
                  "grams_low": <number: lower bound>,
                  "grams_high": <number: upper bound>,
                  "confidence": <number 0.0-1.0>,
                  "hidden": <boolean>
                }
              ]
            }
            """;

    private String buildStage1Prompt(String textDescription) {
        // Volatile content goes strictly after the static block so the prefix stays stable.
        if (textDescription == null || textDescription.isBlank()) {
            return STAGE1_STATIC + "\n## USER DESCRIPTION\nNone — rely on the image.\n";
        }
        return STAGE1_STATIC + """

                ## USER DESCRIPTION
                The user describes this meal as:
                "%s"
                Treat this as strong evidence for identity and preparation, and let it
                override the image where the image is ambiguous. If it contradicts
                something clearly visible, follow the image and note the conflict in
                extraction_notes.
                """.formatted(textDescription);
    }

    // =========================================================================
    //  STAGE 2 — judgement
    // =========================================================================

    /**
     * Static half of the Stage-2 prompt. Note what is absent versus the previous version:
     * no Atwater contract, no sodium law, no seven-step scratchpad, no per-ingredient
     * nutrient schema. All of that moved into {@link NutrientResolver}, which is both
     * exact and free.
     */
    private static final String STAGE2_STATIC = """
            You are a clinical dietitian writing a short assessment of one meal.

            The nutrition facts below were computed from a food composition database, not
            estimated. They are correct. Do NOT recalculate them, second-guess them, or
            restate the arithmetic. Your job is judgement: what this meal does for this
            person, and what to do next.

            ## SCORING (0-100)
            First decide meal_context:
              "main"  -> Breakfast, Lunch, Dinner, Post Workout, OR >=25% of daily calories
              "light" -> everything else (Snack, Mid-Morning, Midnight)
            A snack is not a failed dinner. Judge each meal by its own job: protein is the
            backbone of a main meal but only a bonus in a light one.

            MAIN (per-meal protein target ~= daily protein / 4):
              1. Protein (30):   >=35 g->30 | 25-34->22 | 15-24->14 | 10-14->8 | 5-9->4 | <5->1
              2. Glycaemic (25): GL<10->23-25 | 10-14->17-20 | 15-19->10-14 | 20-29->4-8 | >=30->1-3
              3. Macro balance (20): protein >=25% kcal, fat 20-35%, carbs 35-55%:
                 all in->20 | 1 out->14 | 2 out->8 | 3 out->3
              4. Fibre & micros (15): >=6 g->12-15 | 4-5.9->9-11 | 2-3.9->6-8 | 1-1.9->3-5 | <1->1-2
                 (+<=3 bonus for micronutrient diversity, anti-inflammatory spices, fermented foods)
              5. Sodium & sat fat (10): <400 mg AND <3 g->10 | one of the two->7 |
                 400-700 AND 3-5 g->4 | >700 OR >5 g->1-2

            LIGHT:
              1. Food quality (30): all whole/minimally processed->26-30 | mostly whole->18-25 |
                 mixed->10-17 | mostly ultra-processed->1-9
              2. Glycaemic (25): same bands as MAIN
              3. Fibre & micros (20): >=5 g->17-20 | 3-4.9->12-16 | 1.5-2.9->7-11 | <1.5->1-6
              4. Protein contribution (15): >=15 g->15 | 8-14.9->11 | 4-7.9->7 | 1-3.9->4 | <1->1
              5. Sodium & sat fat (10): same as MAIN

            WHOLE-FOOD FLOOR — compute the banded score, then take the HIGHER of it and any
            floor that applies:
              - Entirely whole/minimally processed, free sugar <=5 g, sodium <400 mg,
                sat fat <3 g -> at least 85 if light, at least 70 if main.
                A single whole fruit is an A-grade snack.
              - >=75% whole food by calories, free sugar <=12 g, sodium <500 mg,
                sat fat <5 g -> at least 60. Whole food is never graded "poor".
              - Floors NEVER apply to ultra-processed, deep-fried, added-sugar, or
                refined-grain-dominant meals.

            Grades: A 85-100 excellent | B 70-84 good | C 55-69 fair | D 40-54 poor | F <40 very poor.

            ## GLYCAEMIC FRAMING
            Scrutinise refined carbs and FREE sugar (added sugar, honey, jaggery, syrup,
            fruit juice): rapid glucose -> insulin spike -> downstream effects. Say so plainly.
            Protect whole foods equally firmly: fruit, vegetables, legumes, nuts and intact
            whole grains carry sugar inside a fibre matrix. Never call a whole apple or a
            bowl of dal an insulin spike, and never stack glycaemic + sugar + empty-calorie
            penalties on the same whole food.

            ## RULES
            1. Raw JSON only. Nothing before the opening brace or after the closing one.
            2. Never output a calorie or macro number that contradicts the given totals.
            3. Recommendations are specific and quantified: "add 150 g curd for +5 g protein",
               never "eat more protein".
            4. Findings cite a mechanism, not a verdict.
            5. Respect the dietary restriction stated in the profile without exception.
            6. Strengths must be genuine. Never fabricate one, never omit a real one.
            7. score_rationale must name the context ("As a snack, ..." / "As a main meal, ...").

            ## OUTPUT SCHEMA
            {
              "meal_type": "<Breakfast|Lunch|Dinner|Snack>",
              "meal_score": {
                "meal_context": "<main|light>",
                "overall_score": <int 0-100>,
                "letter_grade": "<A|B|C|D|F>",
                "score_rationale": "<2-3 sentences naming the context>",
                "macro_balance_score": <int 0-100>,
                "glycaemic_score": <int 0-100>,
                "micronutrient_density_score": <int 0-100>,
                "condition_safety_score": <int 0-100>
              },
              "insulin_impact_summary": "<1-2 sentences on the post-prandial response>",
              "clinical_flags": [
                {
                  "flag_id": "FLAG_001",
                  "severity": "<LOW|MODERATE|HIGH|CRITICAL>",
                  "category": "<GLYCAEMIC|CARDIOVASCULAR|RENAL|INFLAMMATORY|HORMONAL|DIGESTIVE|MACRO_IMBALANCE|MICRONUTRIENT|GENERAL>",
                  "condition_link": "<condition from the profile, or 'General Population'>",
                  "title": "<short title>",
                  "evidence_basis": "<guideline or mechanism>",
                  "mechanistic_pathway": "<why this matters physiologically>",
                  "affected_ingredients": ["<name>"],
                  "quantified_risk": "<e.g. '820 mg sodium is 36% of the 2300 mg daily limit'>",
                  "urgency": "<Monitor|Reduce|Avoid|Consult Physician>"
                }
              ],
              "positive_highlights": [
                {"highlight_id": "POS_001", "ingredient_or_aspect": "<string>",
                 "benefit": "<specific benefit>", "evidence": "<mechanism>"}
              ],
              "recommendations": [
                {"rec_id": "REC_001", "priority": "<Critical|High|Medium|Low>",
                 "type": "<Substitute|Reduce_Portion|Add_Ingredient|Remove_Ingredient|Timing|Hydration|Next_Meal_Guidance>",
                 "title": "<short>", "action": "<quantified action>",
                 "rationale": "<why>", "example": "<concrete swap>",
                 "condition_targeted": "<condition or 'General Wellness'>"}
              ],
              "next_meal_guidance": {
                "suggested_calorie_range_kcal": "<e.g. '400-500 kcal'>",
                "priority_nutrients_to_target": ["<e.g. 'Protein: 25-30 g'>"],
                "foods_to_favour": ["<specific>"],
                "foods_to_limit": ["<specific>"],
                "timing_recommendation": "<string>",
                "hydration_note": "<string>"
              }
            }
            """;

    private String buildStage2Prompt(String stage1Json, ResolvedMeal resolved, UserAccount user) {
        return STAGE2_STATIC
                + "\n## THIS MEAL — computed facts, treat as ground truth\n"
                + resolved.toPromptBlock()
                + coverageCaveat(resolved)
                + "\n## THIS PERSON\n"
                + buildProfileBlock(user)
                + "\n## CONTEXT FROM THE PHOTO\n"
                + extractContextLine(stage1Json)
                + "\nWrite the assessment now.\n";
    }

    /** Tells the model when the totals under-count, so it does not over-claim precision. */
    private String coverageCaveat(ResolvedMeal resolved) {
        if (resolved.getUnresolved().isEmpty()) return "";
        return String.format(
                "NOTE: %d component(s) had no database entry and are EXCLUDED from the totals "
                        + "above (%s). The true totals are therefore somewhat higher. Say so if it "
                        + "is material; do not silently treat the totals as complete.%n",
                resolved.getUnresolved().size(), String.join(", ", resolved.getUnresolved()));
    }

    private String buildProfileBlock(UserAccount user) {
        if (user == null) {
            return "No profile on file. Use general-population guidance: no assumed conditions, "
                    + "no condition-specific fear framing, proportionate and practical concerns. "
                    + "Daily reference targets: 2000 kcal, 50 g protein, 2300 mg sodium, 25 g fibre.\n";
        }
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, "Name", user.getDisplayName());
        Integer age = user.getPhysicalMetrics() != null && user.getPhysicalMetrics().getAge() != null
                ? user.getPhysicalMetrics().getAge() : user.getAge();
        appendIfPresent(sb, "Age", age == null ? null : age + " years");
        if (user.getPhysicalMetrics() != null) {
            appendIfPresent(sb, "Sex", user.getPhysicalMetrics().getGender());
        }
        Double weight = user.getPhysicalMetrics() != null && user.getPhysicalMetrics().getWeight() != null
                ? user.getPhysicalMetrics().getWeight() : user.getWeight();
        appendIfPresent(sb, "Weight", weight == null ? null : String.format("%.1f kg", weight));
        appendIfPresent(sb, "Goal", user.getFitnessGoal() == null ? null : user.getFitnessGoal().name());
        appendIfPresent(sb, "Activity", user.getActivityLevel() == null ? null : user.getActivityLevel().name());

        sb.append("Daily targets: ")
                .append(String.format("%.0f kcal", resolveTarget(user, "calories")))
                .append(String.format(", %.0f g protein", resolveTarget(user, "protein")))
                .append(", 2300 mg sodium, 25 g fibre\n");

        if (user.getMedicalConditions() != null && !user.getMedicalConditions().isEmpty()) {
            sb.append("Active conditions: ").append(String.join(", ", user.getMedicalConditions()))
                    .append("\nTailor findings and recommendations to THESE conditions only. ")
                    .append("Do not introduce conditions the user did not report.\n");
        } else {
            sb.append("No conditions reported. Do not assume any. Include a clinical flag only "
                    + "where the food genuinely warrants it.\n");
        }
        return sb.toString();
    }

    private double resolveTarget(UserAccount user, String which) {
        if ("calories".equals(which)) {
            if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedCalories() != null) {
                return user.getDynamicTargets().getCalculatedCalories();
            }
            if (user.getTargetCalories() != null) return user.getTargetCalories();
            return user.getTdee() != null ? user.getTdee() : 2000.0;
        }
        if (user.getDynamicTargets() != null && user.getDynamicTargets().getCalculatedProtein() != null) {
            return user.getDynamicTargets().getCalculatedProtein();
        }
        return user.getTargetProtein() != null ? user.getTargetProtein() : 50.0;
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append(label).append(": ").append(value).append('\n');
    }

    /** Pulls just the descriptive fields from Stage 1 — Stage 2 has no use for the rest. */
    private String extractContextLine(String stage1Json) {
        try {
            JsonNode n = objectMapper.readTree(stage1Json);
            return String.format("Dish: %s | Cuisine: %s | Likely meal: %s | Image: %s | Scale ref: %s%n%s%n",
                    n.path("meal_label").asText("Unknown"),
                    n.path("cuisine_type").asText("Unknown"),
                    n.path("meal_type_guess").asText("Unknown"),
                    n.path("image_quality").asText("Unknown"),
                    n.path("scale_reference").asText("none"),
                    n.path("extraction_notes").asText(""));
        } catch (Exception e) {
            return "Dish context unavailable.\n";
        }
    }

    // =========================================================================
    //  Parsing and merging
    // =========================================================================

    private List<ExtractedItem> parseExtraction(String stage1Json) {
        List<ExtractedItem> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(stage1Json);
            // Accept the legacy "ingredients" key too, so a provider echoing the old
            // schema still produces a usable meal rather than an empty one.
            JsonNode array = root.has("items") ? root.path("items") : root.path("ingredients");
            for (JsonNode n : array) {
                String name = n.hasNonNull("name") ? n.path("name").asText()
                        : n.path("common_name").asText(n.path("usda_food_description").asText(""));
                double grams = n.has("grams") ? n.path("grams").asDouble()
                        : n.path("estimated_weight_g").asDouble(0);
                if (name.isBlank() || grams <= 0) continue;
                items.add(new ExtractedItem(
                        name, grams,
                        n.path("hidden").asBoolean(n.path("is_hidden").asBoolean(false)),
                        n.has("confidence") ? n.path("confidence").asDouble()
                                : n.has("confidence_score") ? n.path("confidence_score").asDouble() : null));
            }
        } catch (Exception e) {
            log.error("[NutritionPipeline] Stage 1 JSON unparseable: {}", e.getMessage());
        }
        return items;
    }

    /**
     * Combines the model's judgement with Java's numbers into the response contract the
     * controller and frontend already expect. Numbers always come from the resolver — if
     * Stage 2 emitted anything numeric, it is discarded here.
     */
    private GeminiAnalysisResult mergeResult(String stage2Json, String stage1Json, ResolvedMeal resolved)
            throws Exception {
        ObjectNode out;
        try {
            out = (ObjectNode) objectMapper.readTree(stage2Json);
        } catch (Exception e) {
            // Judgement is a nice-to-have; the resolved numbers are the product. Losing
            // Stage 2 should degrade the response, not fail the whole scan.
            log.error("[NutritionPipeline] Stage 2 JSON unparseable ({}); returning numbers only",
                    e.getMessage());
            out = objectMapper.createObjectNode();
        }

        JsonNode stage1 = objectMapper.readTree(stage1Json);
        out.put("pipeline_stage", "STAGE_2_CLINICAL_ASSESSMENT");
        out.put("meal_label", stage1.path("meal_label").asText("Meal"));
        out.put("cuisine_type", stage1.path("cuisine_type").asText("Unknown"));
        out.put("analysis_timestamp_utc", java.time.Instant.now().toString());

        // ── Totals: authoritative, from the resolver ─────────────────────────
        ObjectNode totals = out.putObject("macro_totals");
        totals.put("calories_kcal", resolved.total("kcal"));
        totals.put("protein_g", resolved.total("protein"));
        totals.put("carbohydrates_g", resolved.total("carbs"));
        totals.put("fat_g", resolved.total("fat"));
        totals.put("saturated_fat_g", resolved.total("satFat"));
        totals.put("dietary_fiber_g", resolved.total("fiber"));
        totals.put("sugar_g", resolved.total("sugar"));
        totals.put("sodium_mg", resolved.total("sodium"));
        totals.put("potassium_mg", resolved.total("potassium"));
        totals.put("cholesterol_mg", resolved.total("cholesterol"));
        totals.put("trans_fat_g", 0.0);
        totals.put("unsaturated_fat_g",
                Math.max(0, resolved.total("fat") - resolved.total("satFat")));
        totals.put("added_sugar_g", 0.0);

        ObjectNode verification = totals.putObject("math_verification");
        verification.put("expected_calories_from_macros", resolved.getAtwaterKcal());
        verification.put("stated_calories", resolved.total("kcal"));
        verification.put("delta_kcal", resolved.getAtwaterDeltaKcal());
        verification.put("gate_passed", resolved.isAtwaterConsistent());

        // ── Per-item breakdown: also from the resolver ───────────────────────
        ArrayNode breakdown = out.putArray("ingredients_breakdown");
        int itemId = 1;
        for (ResolvedItem item : resolved.getItems()) {
            ObjectNode node = breakdown.addObject();
            node.put("item_id", itemId++);
            node.put("name", item.getMatchedName());
            node.put("common_name", item.getExtractedName());
            node.put("estimated_weight_g", item.getGrams());
            node.put("is_hidden", item.isHidden());
            node.put("nutrition_per_100g_source", item.getSource());
            node.put("match_score", item.getMatchScore());
            if (item.getExtractionConfidence() != null) {
                node.put("extraction_confidence", item.getExtractionConfidence());
            }
            ObjectNode n = node.putObject("nutrients");
            n.put("calories_kcal", item.getNutrients().get("kcal"));
            n.put("protein_g", item.getNutrients().get("protein"));
            n.put("carbohydrates_g", item.getNutrients().get("carbs"));
            n.put("fat_g", item.getNutrients().get("fat"));
            n.put("saturated_fat_g", item.getNutrients().get("satFat"));
            n.put("dietary_fiber_g", item.getNutrients().get("fiber"));
            n.put("sugar_g", item.getNutrients().get("sugar"));
            n.put("sodium_mg", item.getNutrients().get("sodium"));
            if (item.getGlycemicIndex() != null) {
                node.put("glycaemic_index_estimate", item.getGlycemicIndex());
                node.put("glycaemic_load_contribution", item.getGlycemicLoad());
            }
        }

        // ── Glycaemic block: numbers from Java, prose from the model ─────────
        ObjectNode glycaemic = out.putObject("glycaemic_assessment");
        glycaemic.put("total_meal_glycaemic_load", resolved.getGlycemicLoad());
        glycaemic.put("gl_classification", resolved.getGlycemicClassification());
        glycaemic.put("insulin_impact_summary",
                out.path("insulin_impact_summary").asText(
                        "Glycaemic load " + resolved.getGlycemicLoad() + "."));
        out.remove("insulin_impact_summary");

        ArrayNode quality = out.putArray("data_quality_flags");
        for (String name : resolved.getUnresolved()) {
            quality.add("No composition-table entry for '" + name + "' — excluded from totals.");
        }
        if (!resolved.isAtwaterConsistent()) {
            quality.add(String.format("Energy and macros differ by %.0f kcal — check the matched rows.",
                    resolved.getAtwaterDeltaKcal()));
        }
        if (resolved.coverage() < 1.0) {
            quality.add(String.format("Table coverage %.0f%% of identified items.",
                    resolved.coverage() * 100));
        }

        out.put("disclaimer",
                "Nutrition values are computed from a food composition database (USDA FoodData "
                        + "Central plus curated regional entries) using AI-estimated portion sizes. "
                        + "Portion estimation from a photograph carries meaningful error. This is a "
                        + "decision-support tool, not medical advice.");

        return objectMapper.treeToValue(out, GeminiAnalysisResult.class);
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
