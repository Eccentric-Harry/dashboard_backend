package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FoodEntryDTO {

    private String id;

    private String description;

    private Integer calories;

    private Integer proteinGrams;

    private String mealType;

    private String date;

    private String mealQuality;

    private String notes;

    private String recipeCategory;

    private String serving;

    private String servingNotes;

    private String sourceNotes;
}
