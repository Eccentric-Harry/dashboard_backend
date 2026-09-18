package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.HealthEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import com.personal_dashboard.backend.repository.DailyFoodLogRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserAccountRepository userAccountRepository;
    private final HealthEngineService healthEngineService;
    private final DailyFoodLogRepository dailyFoodLogRepository;

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserAccount>> getProfile() {
        String userId = UserContext.getRequiredUserId();
        log.info("Fetching profile for user: {}", userId);
        Optional<UserAccount> userOpt = userAccountRepository.findById(userId);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(
                    ApiResponse.<UserAccount>builder()
                            .meta(createMeta("get-profile-failed"))
                            .build()
            );
        }

        UserAccount existing = userOpt.get();
        if (existing.getPhysicalMetrics() != null && (existing.getBmi() == null || existing.getTdee() == null)) {
            log.info("Calculating missing health metrics for user: {}", userId);
            healthEngineService.calculateHealthMetrics(existing);
            existing = userAccountRepository.save(existing);
        }

        return ResponseEntity.ok(
                ApiResponse.<UserAccount>builder()
                        .data(existing)
                        .meta(createMeta("get-profile-success"))
                        .build()
        );
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserAccount>> updateProfile(@RequestBody UserAccount updated) {
        String userId = UserContext.getRequiredUserId();
        log.info("Updating profile for user: {}", userId);
        Optional<UserAccount> userOpt = userAccountRepository.findById(userId);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(
                    ApiResponse.<UserAccount>builder()
                            .meta(createMeta("update-profile-failed"))
                            .build()
            );
        }

        UserAccount existing = userOpt.get();
        if (updated.getDisplayName() != null && !updated.getDisplayName().isBlank()) {
            existing.setDisplayName(updated.getDisplayName().trim());
        }
        existing.setAvatarUrl(updated.getAvatarUrl());
        existing.setEmail(updated.getEmail());
        existing.setBio(updated.getBio());
        existing.setPhoneNumber(updated.getPhoneNumber());
        existing.setTimezone(updated.getTimezone());
        existing.setWorkingHours(updated.getWorkingHours());
        existing.setTitle(updated.getTitle());
        existing.setStatus(updated.getStatus());
        
        // Update physical metrics and options
        existing.setPhysicalMetrics(updated.getPhysicalMetrics());
        existing.setActivityLevel(updated.getActivityLevel());
        existing.setFitnessGoal(updated.getFitnessGoal());
        existing.setMedicalConditions(updated.getMedicalConditions());
        
        // Re-run calculations
        healthEngineService.calculateHealthMetrics(existing);
        
        existing.setUpdatedAt(Instant.now());

        UserAccount saved = userAccountRepository.save(existing);

        // Recalculate today's food log targets if it exists
        String todayStr = java.time.LocalDate.now().toString();
        dailyFoodLogRepository.findByUserIdAndDateString(userId, todayStr).ifPresent(log -> {
            log.setCalorieGoal(saved.getTargetCalories());
            log.setProteinGoal(saved.getTargetProtein());
            dailyFoodLogRepository.save(log);
        });

        return ResponseEntity.ok(
                ApiResponse.<UserAccount>builder()
                        .data(saved)
                        .meta(createMeta("update-profile-success"))
                        .build()
        );
    }

    /** Separate from PUT /profile, which replaces fields wholesale and would wipe this one. */
    @PutMapping("/profile/learner")
    public ResponseEntity<ApiResponse<UserAccount>> updateLearnerProfile(
            @jakarta.validation.Valid @RequestBody com.personal_dashboard.backend.dto.request.UpdateLearnerProfileRequest request) {
        String userId = UserContext.getRequiredUserId();
        log.info("Updating learner profile for user: {}", userId);
        Optional<UserAccount> userOpt = userAccountRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(
                    ApiResponse.<UserAccount>builder()
                            .meta(createMeta("update-learner-profile-failed"))
                            .build()
            );
        }

        UserAccount existing = userOpt.get();
        String value = request.getLearnerProfile();
        existing.setLearnerProfile(value == null || value.isBlank() ? null : value.trim());
        existing.setUpdatedAt(Instant.now());

        return ResponseEntity.ok(
                ApiResponse.<UserAccount>builder()
                        .data(userAccountRepository.save(existing))
                        .meta(createMeta("update-learner-profile-success"))
                        .build()
        );
    }

    /**
     * Sets (or, with a null body value, clears) the explicit daily protein target.
     * Separate from PUT /profile for the same reason as the learner profile: that
     * endpoint replaces fields wholesale and would wipe it.
     */
    @PutMapping("/profile/protein-target")
    public ResponseEntity<ApiResponse<UserAccount>> updateProteinTarget(
            @jakarta.validation.Valid @RequestBody com.personal_dashboard.backend.dto.request.UpdateProteinTargetRequest request) {
        String userId = UserContext.getRequiredUserId();
        log.info("Updating protein target for user: {} -> {}", userId, request.getGrams());
        Optional<UserAccount> userOpt = userAccountRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(
                    ApiResponse.<UserAccount>builder()
                            .meta(createMeta("update-protein-target-failed"))
                            .build()
            );
        }

        UserAccount existing = userOpt.get();
        existing.setProteinTargetOverride(request.getGrams());
        // The engine skips users without complete biometrics, so set the legacy field
        // directly too; with biometrics it recomputes the same value plus the carb split.
        if (request.getGrams() != null) {
            existing.setTargetProtein(request.getGrams());
        }
        healthEngineService.calculateHealthMetrics(existing);
        existing.setUpdatedAt(Instant.now());

        UserAccount saved = userAccountRepository.save(existing);

        // Today's food log follows the profile's current targets.
        String todayStr = java.time.LocalDate.now().toString();
        dailyFoodLogRepository.findByUserIdAndDateString(userId, todayStr).ifPresent(log -> {
            log.setProteinGoal(saved.getTargetProtein());
            dailyFoodLogRepository.save(log);
        });

        return ResponseEntity.ok(
                ApiResponse.<UserAccount>builder()
                        .data(saved)
                        .meta(createMeta("update-protein-target-success"))
                        .build()
        );
    }

    private ApiMeta createMeta(String source) {
        return ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source(source)
                .build();
    }
}
