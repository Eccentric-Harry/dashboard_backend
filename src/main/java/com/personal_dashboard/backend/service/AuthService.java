package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.AuthToken;
import com.personal_dashboard.backend.model.Passcode;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.AuthTokenRepository;
import com.personal_dashboard.backend.repository.PasscodeRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final Duration TOKEN_CACHE_TTL = Duration.ofMinutes(5);
    private static final int TOKEN_CACHE_MAX_ENTRIES = 1_000;

    private final PasscodeRepository passcodeRepository;
    private final AuthTokenRepository authTokenRepository;
    private final UserAccountRepository userAccountRepository;

    private final Map<String, CachedToken> validatedTokens = new ConcurrentHashMap<>();

    public Optional<String> verifyPasscode(String rawPasscode) {
        log.warn("Legacy verifyPasscode endpoint is disabled.");
        return Optional.empty();
    }

    public Optional<String> signup(String username, String displayName, String passcode) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (passcode == null || passcode.trim().length() < 4) {
            throw new IllegalArgumentException("Passcode must be at least 4 characters long");
        }
        String sanitizedUsername = username.trim().toLowerCase();
        if (sanitizedUsername.length() < 3 || sanitizedUsername.length() > 20 || !sanitizedUsername.matches("^[a-zA-Z0-9_-]+$")) {
            log.warn("Invalid username format: {}", sanitizedUsername);
            throw new IllegalArgumentException("Username must be 3-20 characters (alphanumeric, hyphens, or underscores)");
        }

        if (userAccountRepository.existsById(sanitizedUsername)) {
            log.warn("Username already exists: {}", sanitizedUsername);
            throw new IllegalArgumentException("Username is already taken");
        }

        // Create UserAccount
        UserAccount account = UserAccount.builder()
                .id(sanitizedUsername)
                .displayName(displayName != null && !displayName.isBlank() ? displayName.trim() : username.trim())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        userAccountRepository.save(account);

        // Create Passcode
        String passcodeId = UUID.randomUUID().toString();
        Passcode passcodeObj = Passcode.builder()
                .id(passcodeId)
                .userId(sanitizedUsername)
                .hash(hashPasscode(passcode))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        passcodeRepository.save(passcodeObj);

        // Generate Token
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AuthToken authToken = AuthToken.builder()
                .token(token)
                .userId(sanitizedUsername)
                .createdAt(now)
                .expiresAt(now.plusSeconds(7 * 24 * 3600))
                .build();
        authTokenRepository.save(authToken);

        log.info("Signed up new user: {}", sanitizedUsername);
        return Optional.of(token);
    }

    public Optional<String> login(String username, String passcode) {
        if (username == null || passcode == null) {
            return Optional.empty();
        }
        String sanitizedUsername = username.trim().toLowerCase();
        if (sanitizedUsername.isBlank()) {
            return Optional.empty();
        }

        String inputHash = hashPasscode(passcode);
        List<Passcode> userPasscodes = passcodeRepository.findByUserId(sanitizedUsername);
        Optional<Passcode> passcodeOpt = userPasscodes.isEmpty() ? Optional.empty() : Optional.of(userPasscodes.getFirst());
        if (passcodeOpt.isEmpty() || !passcodeOpt.get().getHash().equals(inputHash)) {
            log.warn("Failed login attempt for username: {}", sanitizedUsername);
            return Optional.empty();
        }

        // Ensure user account exists
        if (!userAccountRepository.existsById(sanitizedUsername)) {
            userAccountRepository.save(UserAccount.builder()
                    .id(sanitizedUsername)
                    .displayName(sanitizedUsername)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
        }

        // Generate Token
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AuthToken authToken = AuthToken.builder()
                .token(token)
                .userId(sanitizedUsername)
                .createdAt(now)
                .expiresAt(now.plusSeconds(7 * 24 * 3600))
                .build();
        authTokenRepository.save(authToken);

        log.info("Logged in user: {}", sanitizedUsername);
        return Optional.of(token);
    }

    /**
     * Every authenticated request validates its bearer token, and each lookup is a full
     * round trip to Atlas (~55 ms from the Render region, measured). A validated token is
     * remembered briefly — never past its own expiry — so a route that fans out a dozen
     * calls pays for one lookup instead of twelve. Only hits are cached: an unknown token
     * always goes to the database. There is no logout/revocation path today; if one is
     * added it must evict from {@link #validatedTokens}, or a revoked token keeps working
     * for up to {@link #TOKEN_CACHE_TTL}.
     */
    public Optional<AuthToken> validateToken(String token) {
        Instant now = Instant.now();
        CachedToken cached = validatedTokens.get(token);
        if (cached != null) {
            if (cached.cachedUntil().isAfter(now)) {
                return Optional.of(cached.authToken());
            }
            validatedTokens.remove(token, cached);
        }

        Optional<AuthToken> valid = authTokenRepository.findByToken(token)
                .filter(t -> t.getExpiresAt() != null && t.getExpiresAt().isAfter(now))
                .filter(t -> t.getUserId() != null && !t.getUserId().isBlank());
        valid.ifPresent(t -> rememberValidToken(token, t, now));
        return valid;
    }

    private void rememberValidToken(String token, AuthToken authToken, Instant now) {
        if (validatedTokens.size() >= TOKEN_CACHE_MAX_ENTRIES) {
            // Crude bound — a personal dashboard has a handful of live sessions, so hitting
            // this means something is minting tokens; starting over is the safe response.
            validatedTokens.clear();
        }
        Instant ttlEnd = now.plus(TOKEN_CACHE_TTL);
        Instant cachedUntil = authToken.getExpiresAt().isBefore(ttlEnd) ? authToken.getExpiresAt() : ttlEnd;
        validatedTokens.put(token, new CachedToken(authToken, cachedUntil));
    }

    private record CachedToken(AuthToken authToken, Instant cachedUntil) {
    }

    private String resolveUserId(Passcode passcode) {
        String userId = passcode.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("Passcode document is missing user ID association");
        }

        if (!userAccountRepository.existsById(userId)) {
            userAccountRepository.save(UserAccount.builder()
                    .id(userId)
                    .displayName(userId)
                    .build());
        }

        return userId;
    }

    public static String hashPasscode(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available in this JVM", e);
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
