package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.FocusSession;
import com.personal_dashboard.backend.model.FocusSessionStatus;
import com.personal_dashboard.backend.repository.FocusSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FocusSessionService {

    private final FocusSessionRepository repository;

    public Optional<FocusSession> getCurrentSession(String userId) {
        if (userId != null && !userId.isBlank()) {
            return repository.findTopByUserIdAndStatusNotOrderByStartTimeDesc(userId, FocusSessionStatus.COMPLETED);
        }
        return repository.findTopByStatusNotOrderByStartTimeDesc(FocusSessionStatus.COMPLETED);
    }

    public FocusSession startSession(String activePursuit, int durationMinutes, String userId) {
        cancelExistingSession(userId);

        Instant now = Instant.now();
        FocusSession session = FocusSession.builder()
                .userId(userId)
                .activePursuit(activePursuit)
                .durationMinutes(durationMinutes)
                .status(FocusSessionStatus.RUNNING)
                .startTime(now)
                .endTime(now.plusSeconds(durationMinutes * 60L))
                .remainingSecondsOnPause(null)
                .build();

        FocusSession saved = repository.save(session);
        log.info("Focus session started: id={}, pursuit={}, duration={}m, endTime={}",
                saved.getId(), activePursuit, durationMinutes, saved.getEndTime());
        return saved;
    }

    public FocusSession pauseSession(String userId) {
        FocusSession session = findActiveRunningSession(userId);

        long remaining = session.getEndTime().getEpochSecond() - Instant.now().getEpochSecond();
        session.setRemainingSecondsOnPause(Math.max(0, remaining));
        session.setStatus(FocusSessionStatus.PAUSED);
        session.setEndTime(null);

        FocusSession saved = repository.save(session);
        log.info("Focus session paused: id={}, remainingSeconds={}", saved.getId(), saved.getRemainingSecondsOnPause());
        return saved;
    }

    public FocusSession resumeSession(String userId) {
        FocusSession session = findActivePausedSession(userId);

        long remaining = session.getRemainingSecondsOnPause() != null ? session.getRemainingSecondsOnPause() : 0L;
        session.setStatus(FocusSessionStatus.RUNNING);
        session.setEndTime(Instant.now().plusSeconds(remaining));
        session.setRemainingSecondsOnPause(null);

        FocusSession saved = repository.save(session);
        log.info("Focus session resumed: id={}, endTime={}", saved.getId(), saved.getEndTime());
        return saved;
    }

    public FocusSession cancelSession(String userId) {
        Optional<FocusSession> current = getCurrentSession(userId);
        if (current.isEmpty()) {
            log.warn("No active session to cancel for userId={}", userId);
            return null;
        }

        FocusSession session = current.get();
        session.setStatus(FocusSessionStatus.IDLE);
        session.setEndTime(null);
        session.setRemainingSecondsOnPause(null);

        FocusSession saved = repository.save(session);
        log.info("Focus session cancelled: id={}", saved.getId());
        return saved;
    }

    public FocusSession completeSession(String userId) {
        Optional<FocusSession> current = getCurrentSession(userId);
        if (current.isEmpty()) {
            log.warn("No active session to complete for userId={}", userId);
            return null;
        }

        FocusSession session = current.get();
        session.setStatus(FocusSessionStatus.COMPLETED);
        session.setEndTime(null);
        session.setRemainingSecondsOnPause(null);

        FocusSession saved = repository.save(session);
        log.info("Focus session completed: id={}", saved.getId());
        return saved;
    }

    private void cancelExistingSession(String userId) {
        Optional<FocusSession> existing = getCurrentSession(userId);
        existing.ifPresent(session -> {
            if (session.getStatus() == FocusSessionStatus.RUNNING || session.getStatus() == FocusSessionStatus.PAUSED) {
                session.setStatus(FocusSessionStatus.IDLE);
                session.setEndTime(null);
                session.setRemainingSecondsOnPause(null);
                repository.save(session);
                log.info("Previous session cancelled for new start: id={}", session.getId());
            }
        });
    }

    private FocusSession findActiveRunningSession(String userId) {
        Optional<FocusSession> current = getCurrentSession(userId);
        return current.filter(s -> s.getStatus() == FocusSessionStatus.RUNNING)
                .orElseThrow(() -> new IllegalStateException("No running session found for userId: " + userId));
    }

    private FocusSession findActivePausedSession(String userId) {
        Optional<FocusSession> current = getCurrentSession(userId);
        return current.filter(s -> s.getStatus() == FocusSessionStatus.PAUSED)
                .orElseThrow(() -> new IllegalStateException("No paused session found for userId: " + userId));
    }
}
