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

    // Three Non-Negotiables targets (rings). Null until the user edits them;
    // read through RingTargets.resolved() so defaults apply everywhere.
    private RingTargets ringTargets;

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

    /**
     * The user's own commitments for the Three Non-Negotiables. Targets are
     * never adapted automatically — the app must not silently lower them.
     * dayRolloverHour shifts the ring-day boundary off midnight so late work
     * and early sleep logs land on the day they belong to.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RingTargets {
        private Integer sleepTargetMinutes;
        private Integer focusTargetMinutes;
        private Integer moveTargetMinutes;
        private Integer dayRolloverHour;

        public static final int DEFAULT_SLEEP_TARGET_MINUTES = 450;
        public static final int DEFAULT_FOCUS_TARGET_MINUTES = 120;
        public static final int DEFAULT_MOVE_TARGET_MINUTES = 15;
        public static final int DEFAULT_DAY_ROLLOVER_HOUR = 4;

        /** A copy with every null field replaced by its default. */
        public RingTargets resolved() {
            return RingTargets.builder()
                    .sleepTargetMinutes(sleepTargetMinutes != null ? sleepTargetMinutes : DEFAULT_SLEEP_TARGET_MINUTES)
                    .focusTargetMinutes(focusTargetMinutes != null ? focusTargetMinutes : DEFAULT_FOCUS_TARGET_MINUTES)
                    .moveTargetMinutes(moveTargetMinutes != null ? moveTargetMinutes : DEFAULT_MOVE_TARGET_MINUTES)
                    .dayRolloverHour(dayRolloverHour != null ? dayRolloverHour : DEFAULT_DAY_ROLLOVER_HOUR)
                    .build();
        }

        public static RingTargets defaults() {
            return new RingTargets().resolved();
        }
    }
}
