package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One document per day in the daily_food_logs collection.
 * The _id is the date string (e.g., "2026-05-08").
 * Meals are grouped by mealType (Breakfast, Lunch, Dinner, etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "daily_food_logs")
public class DailyFoodLog {

    @Id
    private String id; // "2026-05-08" — the date string

    @Indexed(unique = true)
    private String mealId; // Same as id — date string for easy lookups

    private LocalDate date;

    @Builder.Default
    private DailyTotals dailyTotals = new DailyTotals();

    /** Meals grouped by mealType. Keys: "Breakfast", "Lunch", "Dinner", "Snack", etc. */
    @Builder.Default
    private Map<String, List<MealEntry>> meals = new LinkedHashMap<>();

    @Builder.Default
    private HydrationData hydration = new HydrationData();
}
