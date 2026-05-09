package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "food_entries")
public class FoodEntry {

    @Id
    private String id;

    private String description;

    private Integer calories;

    private Integer proteinGrams;

    private String mealType; // "Breakfast", "Lunch", "Dinner", "Snack"

    private LocalDate date;

    private String mealQuality;

    private String notes;

    private String recipeCategory;

    private String serving;

    private String servingNotes;

    private String sourceNotes;

    private String importKey;
}
