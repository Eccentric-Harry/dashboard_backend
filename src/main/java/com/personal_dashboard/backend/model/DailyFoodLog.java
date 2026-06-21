package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import java.time.Instant;

/**
 * One document per day in the daily_food_logs collection.
 * The dateString field is unique per user (e.g., "2026-05-08").
 * Meals are grouped by mealType (Breakfast, Lunch, Dinner, etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "daily_food_logs")
@CompoundIndex(name = "user_date_unique", def = "{'userId': 1, 'dateString': 1}", unique = true)
public class DailyFoodLog implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String dateString;

    private String mealId; // Backward-compatible alias for dateString in API DTOs

    private LocalDate date;

    @Builder.Default
    private DailyTotals dailyTotals = new DailyTotals();

    /** Meals grouped by mealType. Keys: "Breakfast", "Lunch", "Dinner", "Snack", etc. */
    @Builder.Default
    private Map<String, List<MealEntry>> meals = new LinkedHashMap<>();

    @Builder.Default
    private HydrationData hydration = new HydrationData();

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
