package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.UserAccountRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final UserAccountRepository userAccountRepository;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> signup(
            @RequestBody Map<String, String> body) {
        String username = body.get("username");
        String displayName = body.get("displayName");
        String passcode = body.get("passcode");

        if (username == null || username.isBlank() || passcode == null || passcode.isBlank()) {
            log.warn("Signup rejected: username and passcode are required");
            return badRequest("Username and passcode are required");
        }

        Optional<String> tokenOpt;
        try {
            tokenOpt = authService.signup(username, displayName, passcode);
        } catch (IllegalArgumentException e) {
            log.warn("Signup rejected for username '{}': {}", username, e.getMessage());
            return badRequest(e.getMessage());
        }

        if (tokenOpt.isEmpty()) {
            log.error("Signup returned no token for username '{}' despite no exception", username);
            return badRequest("Signup failed. Please try again.");
        }

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("auth-signup")
                .build();

        Map<String, Object> data = Map.of(
            "token", tokenOpt.get(),
            "username", username.trim().toLowerCase(),
            "displayName", displayName != null && !displayName.isBlank() ? displayName.trim() : username.trim()
        );

        return ResponseEntity.ok(
                ApiResponse.<Map<String, Object>>builder()
                        .data(data)
                        .meta(meta)
                        .build());
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @RequestBody Map<String, String> body) {
        String username = body.get("username");
        String passcode = body.get("passcode");

        if (username == null || username.isBlank() || passcode == null || passcode.isBlank()) {
            log.warn("Login rejected: username and passcode are required");
            return badRequest("Username and passcode are required");
        }

        Optional<String> tokenOpt = authService.login(username, passcode);
        if (tokenOpt.isEmpty()) {
            return unauthorized("Invalid username or passcode");
        }

        String sanitizedUsername = username.trim().toLowerCase();
        String displayName = userAccountRepository.findById(sanitizedUsername)
                .map(UserAccount::getDisplayName)
                .orElse(sanitizedUsername);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("auth-login")
                .build();

        Map<String, Object> data = Map.of(
            "token", tokenOpt.get(),
            "username", sanitizedUsername,
            "displayName", displayName
        );

        return ResponseEntity.ok(
                ApiResponse.<Map<String, Object>>builder()
                        .data(data)
                        .meta(meta)
                        .build());
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyPasscode(
            @RequestBody Map<String, String> body) {

        String passcode = body.get("passcode");
        if (passcode == null || passcode.isBlank()) {
            return badRequest("Passcode is required");
        }

        Optional<String> tokenOpt = authService.verifyPasscode(passcode);

        if (tokenOpt.isEmpty()) {
            return unauthorized("Invalid passcode");
        }

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("auth-verify")
                .build();

        Map<String, Object> data = Map.of("token", tokenOpt.get());

        return ResponseEntity.ok(
                ApiResponse.<Map<String, Object>>builder()
                        .data(data)
                        .meta(meta)
                        .build());
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> badRequest(String message) {
        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("auth-verify")
                .build();

        return ResponseEntity.badRequest()
                .body(ApiResponse.<Map<String, Object>>builder()
                        .data(Map.of("message", message))
                        .meta(meta)
                        .build());
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> unauthorized(String message) {
        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("auth-verify")
                .build();

        return ResponseEntity.status(401)
                .body(ApiResponse.<Map<String, Object>>builder()
                        .data(Map.of("message", message))
                        .meta(meta)
                        .build());
    }
}
