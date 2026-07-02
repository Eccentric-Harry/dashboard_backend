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

    // Enhanced Health Biometrics
    private PhysicalMetrics physicalMetrics;
    private ActivityLevel activityLevel;
    private FitnessGoal fitnessGoal;
    private List<String> medicalConditions;
    private DynamicTargets dynamicTargets;
    private Double bmi;
    private Double bmr;
    private Double tdee;

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
