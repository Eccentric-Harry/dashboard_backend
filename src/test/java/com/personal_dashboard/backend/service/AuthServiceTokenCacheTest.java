package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.AuthToken;
import com.personal_dashboard.backend.repository.AuthTokenRepository;
import com.personal_dashboard.backend.repository.PasscodeRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The token cache sits on the auth path of every request, so the properties that matter
 * are the security ones: a cached token is never honoured past its own expiry, and a
 * failed lookup is never cached.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTokenCacheTest {

    @Mock private PasscodeRepository passcodeRepository;
    @Mock private AuthTokenRepository authTokenRepository;
    @Mock private UserAccountRepository userAccountRepository;

    @InjectMocks private AuthService authService;

    private static AuthToken token(String value, Instant expiresAt) {
        return AuthToken.builder().token(value).userId("harry").expiresAt(expiresAt).build();
    }

    @Test
    void validTokenIsLookedUpOnceThenServedFromCache() {
        when(authTokenRepository.findByToken("t1"))
                .thenReturn(Optional.of(token("t1", Instant.now().plusSeconds(3600))));

        for (int i = 0; i < 5; i++) {
            assertEquals("harry", authService.validateToken("t1").orElseThrow().getUserId());
        }
        verify(authTokenRepository, times(1)).findByToken("t1");
    }

    @Test
    void unknownTokenIsNeverCached() {
        when(authTokenRepository.findByToken("nope")).thenReturn(Optional.empty());

        assertTrue(authService.validateToken("nope").isEmpty());
        assertTrue(authService.validateToken("nope").isEmpty());
        verify(authTokenRepository, times(2)).findByToken("nope");
    }

    @Test
    void expiredTokenIsRejectedAndNotCached() {
        when(authTokenRepository.findByToken("old"))
                .thenReturn(Optional.of(token("old", Instant.now().minusSeconds(1))));

        assertTrue(authService.validateToken("old").isEmpty());
        assertTrue(authService.validateToken("old").isEmpty());
        verify(authTokenRepository, times(2)).findByToken("old");
    }

    @Test
    void cachedTokenIsNotHonouredPastItsOwnExpiry() throws InterruptedException {
        // Expires well inside the cache TTL — the cache must cap itself at the token's expiry.
        when(authTokenRepository.findByToken("short"))
                .thenReturn(Optional.of(token("short", Instant.now().plusMillis(150))));

        assertTrue(authService.validateToken("short").isPresent());
        Thread.sleep(250);
        assertTrue(authService.validateToken("short").isEmpty());
        verify(authTokenRepository, times(2)).findByToken("short");
    }
}
