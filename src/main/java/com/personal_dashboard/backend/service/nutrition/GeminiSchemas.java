package com.personal_dashboard.backend.service.nutrition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response schemas for the two model calls.
 *
 * <p>Declaring the shape here rather than describing it in prose does three things: it removes
 * roughly a thousand tokens of JSON-shaped instructions from each prompt, it makes markdown
 * fences and truncated objects impossible so the parser has nothing to repair, and it stops
 * the model inventing fields the DTO does not have.</p>
 */
public final class GeminiSchemas {

    private GeminiSchemas() {}

    // ─── Schema builder helpers ────────────────────────────────────────────

    private static Map<String, Object> obj(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "OBJECT");
        m.put("properties", properties);
        m.put("propertyOrdering", List.copyOf(properties.keySet()));
        if (required != null && !required.isEmpty()) m.put("required", required);
        return m;
    }

    private static Map<String, Object> arr(Map<String, Object> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "ARRAY");
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> str(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "STRING");
        if (description != null) m.put("description", description);
        return m;
    }

    private static Map<String, Object> enumOf(List<String> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "STRING");
        m.put("enum", values);
        return m;
    }

    private static Map<String, Object> num(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "NUMBER");
        if (description != null) m.put("description", description);
        return m;
    }

    private static Map<String, Object> integer(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "INTEGER");
        if (description != null) m.put("description", description);
        return m;
    }

    private static Map<String, Object> bool(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "BOOLEAN");
        if (description != null) m.put("description", description);
        return m;
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ─── Stage 1: extraction ───────────────────────────────────────────────

    /**
     * Note the absence of any calorie or macronutrient field: the vision stage is structurally
     * prevented from emitting nutrition numbers, so it cannot contribute a hallucinated one.
     */
    public static Map<String, Object> extraction() {
        Map<String, Object> ingredient = obj(props(
                "item_id", integer("Sequential integer starting at 1"),
                "usda_food_description", str("Closest USDA FoodData Central description, "
                        + "e.g. 'Rice, white, long-grain, regular, enriched, cooked'"),
                "fdc_id", integer("FDC ID if known with high confidence, otherwise omit"),
                "common_name", str("Colloquial name, e.g. 'Masoor Dal'"),
                "estimated_weight_g", num("Best-estimate edible weight in grams"),
                "confidence_range_g", obj(props(
                        "low", num("Lower plausible weight in grams"),
                        "high", num("Upper plausible weight in grams")), List.of("low", "high")),
                "confidence_score", num("0.0 to 1.0"),
                "is_hidden", bool("True if inferred rather than visually confirmed"),
                "cooking_method", enumOf(List.of("Raw", "Boiled", "Fried", "Grilled",
                        "Baked", "Steamed", "Sauteed", "Unknown")),
                "item_notes", str("Brief note, or empty string")
        ), List.of("item_id", "usda_food_description", "common_name",
                "estimated_weight_g", "confidence_range_g", "confidence_score",
                "is_hidden", "cooking_method"));

        return obj(props(
                "meal_label", str("Short human-readable meal name"),
                "cuisine_type", str("e.g. 'South Indian', 'North Indian', 'Continental', 'Unknown'"),
                "meal_type_guess", enumOf(List.of("Breakfast", "Lunch", "Dinner", "Snack", "Unknown")),
                "image_quality", enumOf(List.of("Clear", "Partially Occluded", "Blurry", "No Image")),
                "extraction_confidence", enumOf(List.of("High", "Medium", "Low")),
                "extraction_notes", str("Ambiguities, image/text conflicts, or quality issues"),
                "ingredients", arr(ingredient)
        ), List.of("meal_label", "cuisine_type", "meal_type_guess", "image_quality",
                "extraction_confidence", "ingredients"));
    }

    // ─── Stage 2: narrative ────────────────────────────────────────────────

    /** Prose only — no numeric fields, because every number is already computed. */
    public static Map<String, Object> narrative() {
        Map<String, Object> recommendation = obj(props(
                "priority", enumOf(List.of("Critical", "High", "Medium", "Low")),
                "type", enumOf(List.of("Substitute", "Reduce_Portion", "Add_Ingredient",
                        "Remove_Ingredient", "Timing", "Hydration", "Next_Meal_Guidance")),
                "title", str("Short title"),
                "action", str("Specific quantified action, never generic"),
                "rationale", str("Why — cite the guideline or physiological mechanism"),
                "example", str("Concrete example with quantities"),
                "condition_targeted", str("Medical condition addressed, or 'General Wellness'")
        ), List.of("priority", "type", "title", "action", "rationale"));

        Map<String, Object> highlight = obj(props(
                "ingredient_or_aspect", str("What was good about the meal"),
                "benefit", str("Specific nutritional or clinical benefit"),
                "evidence", str("Brief mechanism or citation")
        ), List.of("ingredient_or_aspect", "benefit"));

        Map<String, Object> nextMeal = obj(props(
                "suggested_calorie_range_kcal", str("e.g. '400-500 kcal'"),
                "priority_nutrients_to_target", arr(str(null)),
                "foods_to_favour", arr(str(null)),
                "foods_to_limit", arr(str(null)),
                "timing_recommendation", str(null),
                "hydration_note", str(null)
        ), List.of("suggested_calorie_range_kcal"));

        return obj(props(
                "insulin_impact_summary", str("Mechanistic explanation of this meal's "
                        + "post-prandial glucose and insulin response, using the supplied glycaemic load"),
                "recommendations", arr(recommendation),
                "positive_highlights", arr(highlight),
                "next_meal_guidance", nextMeal
        ), List.of("insulin_impact_summary", "recommendations"));
    }
}
