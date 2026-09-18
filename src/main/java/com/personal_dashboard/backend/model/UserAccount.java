package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class UserAccount {
    @Id
    private String id;

    private String displayName;

    private String avatarUrl;
    private String email;
    private String bio;
    private String phoneNumber;
    private Integer age;
    private Double weight;
    private Double height;
    private Integer targetCalories;
    private Integer targetProtein;

    /**
     * Protein target the user set by hand, in grams/day. Null means "calculate it"
     * (2 g per kg of body weight). When set, HealthEngineService uses it in place of the
     * calculated value and carbs absorb the difference. Only written through
     * PUT /users/profile/protein-target so full-profile saves can't wipe it.
     */
    private Integer proteinTargetOverride;

    // Enhanced Health Biometrics
    private PhysicalMetrics physicalMetrics;
    private ActivityLevel activityLevel;
    private FitnessGoal fitnessGoal;
    private List<String> medicalConditions;
    private DynamicTargets dynamicTargets;
    private Double bmi;
    private Double bmr;
    private Double tdee;

    /**
     * Free-text "about me as a learner" (stack, level, how they like to learn). Only
     * written through PUT /users/profile/learner so full-profile saves can't wipe it.
     */
    private String learnerProfile;

    private String timezone;
    private String workingHours;
    private String title;
    private String status;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhysicalMetrics {
        private Integer age;
        private String gender;
        private Double height;
        private Double weight;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DynamicTargets {
        private Integer calculatedCalories;
        private Integer calculatedProtein;
        private Integer calculatedCarbs;
        private Integer calculatedFat;
    }
}
