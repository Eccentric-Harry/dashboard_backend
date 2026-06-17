package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.AuthToken;
import com.personal_dashboard.backend.repository.AuthTokenRepository;
import com.personal_dashboard.backend.repository.PasscodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final PasscodeRepository passcodeRepository;
    private final AuthTokenRepository authTokenRepository;

    public Optional<String> verifyPasscode(String rawPasscode) {
        var passcodes = passcodeRepository.findAll();
        if (passcodes.isEmpty()) {
            log.warn("No passcode configured in database");
            return Optional.empty();
        }

        String storedHash = passcodes.getFirst().getHash();
        String inputHash = hashPasscode(rawPasscode);

        if (!storedHash.equals(inputHash)) {
            return Optional.empty();
        }

        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AuthToken authToken = AuthToken.builder()
                .token(token)
                .createdAt(now)
                .expiresAt(now.plusSeconds(7 * 24 * 3600))
                .build();
        authTokenRepository.save(authToken);

        return Optional.of(token);
    }

    public boolean isValidToken(String token) {
        return authTokenRepository.findByToken(token)
                .map(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElse(false);
    }

    public static String hashPasscode(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
