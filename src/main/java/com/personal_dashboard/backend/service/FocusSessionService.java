package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.CalendarItemOccurrence;
import com.personal_dashboard.backend.dto.FocusSuggestion;
import com.personal_dashboard.backend.dto.request.FocusImportRequest;
import com.personal_dashboard.backend.dto.request.FocusLogRequest;
import com.personal_dashboard.backend.model.FocusSession;
import com.personal_dashboard.backend.model.FocusSessionStatus;
import com.personal_dashboard.backend.model.FocusSource;
import com.personal_dashboard.backend.repository.FocusSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FocusSessionService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /** Shorter calendar blocks are transitions and standups, not focus work. */
    private static final int MIN_SUGGESTION_MINUTES = 20;

    /**
     * Categories that are definitionally not desk work, so they never clutter
     * the suggestion list. Everything else (including uncategorised blocks) is
     * offered and left to the user's judgement rather than guessed at here.
     */
    private static final java.util.Set<String> NON_FOCUS_CATEGORIES = java.util.Set.of("HEALTH", "SOCIAL");

    private final FocusSessionRepository repository;
    private final CalendarItemService calendarItemService;
    private final com.personal_dashboard.backend.repository.DailyTaskRepository dailyTaskRepository;

    public Optional<FocusSession> getCurrentSession(String userId) {
        if (userId != null && !userId.isBlank()) {
            return repository.findTopByUserIdAndStatusNotOrderByStartTimeDesc(userId, FocusSessionStatus.COMPLETED);
        }
        return repository.findTopByStatusNotOrderByStartTimeDesc(FocusSessionStatus.COMPLETED);
    }

    public List<com.personal_dashboard.backend.dto.FocusDaySummary> getDailyHistory(
            String userId, LocalDate startDate, LocalDate endDate) {
        Instant windowStart = startDate.atStartOfDay(ZONE).toInstant();
        Instant windowEnd = endDate.plusDays(1).atStartOfDay(ZONE).toInstant();

        Map<LocalDate, List<FocusSession>> byDay = repository
                .findByUserIdAndStatusAndStartTimeBetween(userId, FocusSessionStatus.COMPLETED, windowStart, windowEnd)
                .stream()
                .filter(s -> s.getStartTime() != null)
                .collect(Collectors.groupingBy(s -> LocalDate.ofInstant(s.getStartTime(), ZONE)));

        return byDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> com.personal_dashboard.backend.dto.FocusDaySummary.builder()
                        .date(e.getKey().toString())
                        .totalMinutes(e.getValue().stream().mapToLong(s -> Math.max(0, s.getDurationMinutes())).sum())
                        .sessions(e.getValue().size())
                        .timerMinutes(minutesFrom(e.getValue(), FocusSource.TIMER))
                        .manualMinutes(minutesFrom(e.getValue(), FocusSource.MANUAL))
                        .calendarMinutes(minutesFrom(e.getValue(), FocusSource.CALENDAR))
                        .build())
                .toList();
    }

    /** Rows written before the source field existed are all timer sessions. */
    private static long minutesFrom(List<FocusSession> sessions, FocusSource source) {
        return sessions.stream()
                .filter(s -> (s.getSource() == null ? FocusSource.TIMER : s.getSource()) == source)
                .mapToLong(s -> Math.max(0, s.getDurationMinutes()))
                .sum();
    }

    // ---------- Retroactive capture ----------

    /**
     * Record focus work that already happened. Written straight to COMPLETED —
     * it is history, not a timer to run — and never disturbs a session that is
     * currently running, so logging yesterday's office block mid-pomodoro is safe.
     */
    public FocusSession logPastSession(FocusLogRequest request, String userId) {
        LocalDate date = LocalDate.parse(request.getDate());
        if (date.isAfter(LocalDate.now(ZONE))) {
            throw new IllegalArgumentException("Cannot log focus for a future date: " + date);
        }

        // Midday default: the summary only buckets by day, but a real clock time
        // keeps the row honest if per-hour analysis ever reads these.
        java.time.LocalTime start = request.getStartTime() != null && !request.getStartTime().isBlank()
                ? java.time.LocalTime.parse(request.getStartTime())
                : java.time.LocalTime.NOON;
        Instant startInstant = date.atTime(start).atZone(ZONE).toInstant();

        FocusSession session = FocusSession.builder()
                .userId(userId)
                .activePursuit(request.getActivePursuit() != null && !request.getActivePursuit().isBlank()
                        ? request.getActivePursuit()
                        : "Deep work")
                .durationMinutes(request.getMinutes())
                .status(FocusSessionStatus.COMPLETED)
                .source(FocusSource.MANUAL)
                .note(request.getNote())
                .startTime(startInstant)
                .endTime(startInstant.plusSeconds(request.getMinutes() * 60L))
                .build();

        FocusSession saved = repository.save(session);
        log.info("Focus logged retroactively: id={}, date={}, minutes={}", saved.getId(), date, request.getMinutes());
        return saved;
    }

    public void deleteSession(String id, String userId) {
        FocusSession session = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Focus session not found with id: " + id));
        repository.delete(session);
        log.info("Focus session deleted: id={}, source={}", id, session.getSource());
    }

    // ---------- Calendar-derived capture ----------

    /**
     * Timed calendar blocks in the window that plausibly held focused work and
     * have not already been imported. Covers the native calendar and Google
     * Calendar in one pass — synced Google events land in the same
     * {@code daily_tasks} collection, so there is nothing extra to read.
     */
    public List<FocusSuggestion> getCalendarSuggestions(LocalDate startDate, LocalDate endDate, String userId) {
        List<CalendarItemOccurrence> occurrences = calendarItemService.getOccurrences(startDate, endDate);

        Map<String, String> originByTaskId = dailyTaskRepository
                .findCalendarCandidates(userId, startDate, endDate.plusDays(1)).stream()
                .filter(t -> t.getId() != null)
                .collect(Collectors.toMap(
                        com.personal_dashboard.backend.model.DailyTask::getId,
                        t -> t.getOrigin() != null && t.getOrigin().isGoogle() ? "GOOGLE" : "LOCAL",
                        (a, b) -> a));

        List<FocusSuggestion> candidates = occurrences.stream()
                .filter(o -> !Boolean.TRUE.equals(o.getAllDay()))
                .filter(o -> !Boolean.TRUE.equals(o.getCancelled()))
                .filter(o -> o.getStartTime() != null && o.getEndTime() != null)
                .filter(o -> o.getCategory() == null
                        || !NON_FOCUS_CATEGORIES.contains(o.getCategory().toUpperCase(java.util.Locale.ROOT)))
                .map(o -> FocusSuggestion.builder()
                        .occurrenceId(o.getOccurrenceId())
                        .date(o.getDate() != null ? o.getDate().toString() : null)
                        .title(o.getTitle())
                        .startTime(o.getStartTime())
                        .endTime(o.getEndTime())
                        .minutes(minutesBetween(o.getStartTime(), o.getEndTime()))
                        .category(o.getCategory())
                        .origin(originByTaskId.getOrDefault(o.getId(), "LOCAL"))
                        .build())
                .filter(s -> s.getMinutes() >= MIN_SUGGESTION_MINUTES)
                .filter(s -> s.getDate() != null && s.getOccurrenceId() != null)
                .toList();

        java.util.Set<String> alreadyImported = importedOccurrenceIds(
                userId, candidates.stream().map(FocusSuggestion::getOccurrenceId).toList());

        return candidates.stream()
                .filter(s -> !alreadyImported.contains(s.getOccurrenceId()))
                .toList();
    }

    /**
     * Turn confirmed suggestions into COMPLETED sessions. Durations are
     * re-derived from the calendar rather than taken from the client, and
     * occurrences already imported are skipped, so repeating an import cannot
     * double-count a block.
     */
    public List<FocusSession> importCalendarBlocks(FocusImportRequest request, String userId) {
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new IllegalArgumentException("startDate and endDate are required to re-derive the accepted blocks");
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(request.getOccurrenceIds());

        List<FocusSession> created = getCalendarSuggestions(
                LocalDate.parse(request.getStartDate()), LocalDate.parse(request.getEndDate()), userId).stream()
                .filter(s -> wanted.contains(s.getOccurrenceId()))
                .map(s -> {
                    Instant start = LocalDate.parse(s.getDate())
                            .atTime(java.time.LocalTime.parse(s.getStartTime()))
                            .atZone(ZONE)
                            .toInstant();
                    return FocusSession.builder()
                            .userId(userId)
                            .activePursuit(s.getTitle() != null && !s.getTitle().isBlank() ? s.getTitle() : "Deep work")
                            .durationMinutes(s.getMinutes())
                            .status(FocusSessionStatus.COMPLETED)
                            .source(FocusSource.CALENDAR)
                            .sourceRefId(s.getOccurrenceId())
                            .note("Imported from calendar")
                            .startTime(start)
                            .endTime(start.plusSeconds(s.getMinutes() * 60L))
                            .build();
                })
                .toList();

        List<FocusSession> saved = repository.saveAll(created);
        log.info("Imported {} calendar block(s) as focus sessions for userId={}", saved.size(), userId);
        return saved;
    }

    private java.util.Set<String> importedOccurrenceIds(String userId, List<String> occurrenceIds) {
        if (occurrenceIds.isEmpty()) {
            return java.util.Set.of();
        }
        return repository.findByUserIdAndSourceRefIdIn(userId, occurrenceIds).stream()
                .map(FocusSession::getSourceRefId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Same-day clock difference; an end at or before start yields 0 (filtered out). */
    private static int minutesBetween(String startTime, String endTime) {
        try {
            java.time.LocalTime start = java.time.LocalTime.parse(startTime);
            java.time.LocalTime end = java.time.LocalTime.parse(endTime);
            long minutes = java.time.Duration.between(start, end).toMinutes();
            return minutes > 0 ? (int) minutes : 0;
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
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
