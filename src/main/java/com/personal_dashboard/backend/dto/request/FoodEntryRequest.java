package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FoodEntryRequest {

    @NotBlank(message = "Description is required")
    private String description;

    @NotNull(message = "Calories is required")
    @Min(value = 0, message = "Calories must be non-negative")
    @Max(value = 10000, message = "Calories must be less than 10000")
    private Integer calories;

    @NotNull(message = "Protein grams is required")
    @Min(value = 0, message = "Protein grams must be non-negative")
    @Max(value = 500, message = "Protein grams must be less than 500")
    private Integer proteinGrams;

    @NotBlank(message = "Meal type is required")
    @Pattern(regexp = "Breakfast|Lunch|Dinner|Snack|Midnight|Post Workout|Mid-Morning", message = "Meal type must be one of: Breakfast, Lunch, Dinner, Snack, Midnight, Post Workout, Mid-Morning")
    private String mealType;

    @NotBlank(message = "Date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in format YYYY-MM-DD")
    private String date;
}
