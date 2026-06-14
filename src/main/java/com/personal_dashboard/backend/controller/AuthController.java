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
