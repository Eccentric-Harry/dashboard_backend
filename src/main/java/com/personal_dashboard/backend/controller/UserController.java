package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserAccountRepository userAccountRepository;

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

        return ResponseEntity.ok(
                ApiResponse.<UserAccount>builder()
                        .data(userOpt.get())
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
        existing.setAge(updated.getAge());
        existing.setWeight(updated.getWeight());
        existing.setHeight(updated.getHeight());
        existing.setTargetCalories(updated.getTargetCalories());
        existing.setTargetProtein(updated.getTargetProtein());
        existing.setTimezone(updated.getTimezone());
        existing.setWorkingHours(updated.getWorkingHours());
        existing.setTitle(updated.getTitle());
        existing.setUpdatedAt(Instant.now());

        UserAccount saved = userAccountRepository.save(existing);

        return ResponseEntity.ok(
                ApiResponse.<UserAccount>builder()
                        .data(saved)
                        .meta(createMeta("update-profile-success"))
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
