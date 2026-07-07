package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.MindSummaryResponse;
import com.personal_dashboard.backend.dto.request.DailyLogRequest;
import com.personal_dashboard.backend.dto.request.DailyTaskRequest;
import com.personal_dashboard.backend.dto.request.MindEntryRequest;
import com.personal_dashboard.backend.dto.request.MindMoodRequest;
import com.personal_dashboard.backend.dto.request.MindStatusRequest;
import com.personal_dashboard.backend.model.DailyLog;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.FocusSessionStatus;
import com.personal_dashboard.backend.model.Learning;
import com.personal_dashboard.backend.model.MindEntry;
import com.personal_dashboard.backend.model.StravaActivity;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.FocusSessionRepository;
import com.personal_dashboard.backend.repository.LearningRepository;
import com.personal_dashboard.backend.repository.MindEntryRepository;
import com.personal_dashboard.backend.repository.StravaActivityRepository;
import com.personal_dashboard.backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MindService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final int EVIDENCE_WINDOW_DAYS = 7;
    private static final int MAX_TASK_TITLE_LENGTH = 140;

    private final MindEntryRepository mindEntryRepository;
    private final DailyTaskService dailyTaskService;
    private final DailyLogService dailyLogService;
    private final DailyTaskRepository dailyTaskRepository;
    private final FocusSessionRepository focusSessionRepository;
    private final LearningRepository learningRepository;
    private final StravaActivityRepository stravaActivityRepository;

    // ---------- Reads ----------

    public List<MindEntry> getEntries(String type, String status) {
        String userId = UserContext.getRequiredUserId();
        resurfaceDueParked(userId);

        List<MindEntry> entries = mindEntryRepository.findByUserId(userId);
        return entries.stream()
                .filter(e -> type == null || type.equalsIgnoreCase(e.getType()))
                .filter(e -> status == null || status.equalsIgnoreCase(e.getStatus()))
                .sorted(Comparator.comparing(MindEntry::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Any parked worry whose review date has arrived returns to the inbox as OPEN — the
     * "still true? still yours to carry?" moment. Occurs lazily on read so no scheduler is needed.
     */
    private void resurfaceDueParked(String userId) {
        List<MindEntry> due = mindEntryRepository.findDueParked(userId, LocalDate.now(ZONE));
        if (due.isEmpty()) {
            return;
        }
        due.forEach(e -> {
            e.setStatus("OPEN");
            e.setReviewDate(null);
        });
        mindEntryRepository.saveAll(due);
        log.info("Resurfaced {} parked worries for user {}", due.size(), userId);
    }

    // ---------- Writes ----------

    public MindEntry createEntry(MindEntryRequest request) {
        String userId = UserContext.getRequiredUserId();
        LocalDate date = request.getDate() != null && !request.getDate().isBlank()
                ? LocalDate.parse(request.getDate())
                : LocalDate.now(ZONE);
        String type = request.getType() != null && !request.getType().isBlank()
                ? request.getType().toUpperCase()
                : "THOUGHT";

        MindEntry entry = MindEntry.builder()
                .userId(userId)
                .type(type)
                .text(request.getText().trim())
                .status("OPEN")
                .valueTag(request.getValueTag())
                .pinned(request.getPinned())
                .date(date)
                .build();
        return mindEntryRepository.save(entry);
    }

    public MindEntry updateEntry(String id, MindEntryRequest request) {
        MindEntry entry = requireEntry(id);
        entry.setText(request.getText().trim());
        if (request.getType() != null && !request.getType().isBlank()) {
            entry.setType(request.getType().toUpperCase());
        }
        if (request.getValueTag() != null) {
            entry.setValueTag(request.getValueTag());
        }
        if (request.getPinned() != null) {
            entry.setPinned(request.getPinned());
        }
        return mindEntryRepository.save(entry);
    }

    public MindEntry updateStatus(String id, MindStatusRequest request) {
        MindEntry entry = requireEntry(id);
        String status = request.getStatus().toUpperCase();
        entry.setStatus(status);

        switch (status) {
            case "PARKED" -> entry.setReviewDate(
                    request.getReviewDate() != null ? LocalDate.parse(request.getReviewDate()) : null);
            case "OPEN" -> {
                entry.setReviewDate(null);
                entry.setResolvedAt(null);
            }
            case "RESOLVED" -> {
                if (request.getReframedText() != null) {
                    entry.setReframedText(request.getReframedText().trim());
                }
                if (request.getDistortionTag() != null) {
                    entry.setDistortionTag(request.getDistortionTag());
                }
                entry.setResolvedAt(Instant.now());
            }
            case "RELEASED" -> entry.setResolvedAt(Instant.now());
            default -> { /* leave timestamps untouched for any other status */ }
        }

        if (request.getPinned() != null) {
            entry.setPinned(request.getPinned());
        }
        return mindEntryRepository.save(entry);
    }

    /**
     * The "Do" action: an actionable worry becomes a real task. Creates a DailyTask via the
     * existing task service (so it appears in the calendar/tasks views) and records the link.
     */
    public MindEntry convertToTask(String id) {
        MindEntry entry = requireEntry(id);

        String title = entry.getText().trim();
        if (title.length() > MAX_TASK_TITLE_LENGTH) {
            title = title.substring(0, MAX_TASK_TITLE_LENGTH - 1).trim() + "…";
        }
        LocalDate date = entry.getDate() != null ? entry.getDate() : LocalDate.now(ZONE);

        DailyTaskRequest taskRequest = DailyTaskRequest.builder()
                .title(title)
                .date(date.toString())
                .category("Mind")
                .notes("Captured from your Mind inbox.")
                .completed(false)
                .build();
        DailyTask task = dailyTaskService.createTask(taskRequest);

        entry.setStatus("CONVERTED");
        entry.setLinkedTaskId(task.getId());
        entry.setResolvedAt(Instant.now());
        return mindEntryRepository.save(entry);
    }

    public void deleteEntry(String id) {
        MindEntry entry = requireEntry(id);
        mindEntryRepository.delete(entry);
    }

    public DailyLog saveMood(LocalDate date, MindMoodRequest request) {
        DailyLogRequest logRequest = DailyLogRequest.builder()
                .moodScore(request.getMoodScore())
                .moodNote(request.getMoodNote())
                .build();
        return dailyLogService.upsertForDate(date, logRequest);
    }

    // ---------- Summary (auto-evidence + loop stats + streak) ----------

    public MindSummaryResponse getSummary(LocalDate date) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = date != null ? date : LocalDate.now(ZONE);
        LocalDate windowStart = today.minusDays(EVIDENCE_WINDOW_DAYS - 1L);

        long focusMinutes = focusSessionRepository
                .findByUserIdAndStatusAndStartTimeBetween(
                        userId, FocusSessionStatus.COMPLETED, instantAtStart(windowStart), instantAtEnd(today))
                .stream()
                .mapToLong(s -> Math.max(0, s.getDurationMinutes()))
                .sum();

        long tasksCompleted = dailyTaskRepository
                .findByUserIdAndDateRange(userId, windowStart, today.plusDays(1))
                .stream()
                .filter(t -> Boolean.TRUE.equals(t.getCompleted()))
                .count();

        long workouts = stravaActivityRepository
                .findByUserIdAndDateBetween(userId, windowStart, today)
                .stream()
                .count();

        long learnings = learningRepository
                .findByUserIdAndDateRange(userId, windowStart, today)
                .stream()
                .count();

        // Loop-closing stats over the window's captured thoughts.
        List<MindEntry> windowThoughts = mindEntryRepository
                .findByUserIdAndDateRange(userId, windowStart, today)
                .stream()
                .filter(e -> "THOUGHT".equalsIgnoreCase(e.getType()))
                .toList();
        long captured = windowThoughts.size();
        long converted = windowThoughts.stream().filter(e -> "CONVERTED".equalsIgnoreCase(e.getStatus())).count();
        long reframed = windowThoughts.stream().filter(e -> e.getReframedText() != null && !e.getReframedText().isBlank()).count();
        long released = windowThoughts.stream().filter(e -> "RELEASED".equalsIgnoreCase(e.getStatus())).count();

        long streakDays = computeStreak(userId, today);

        Integer moodScore = dailyLogService.findByDate(today).map(DailyLog::getMoodScore).orElse(null);

        return MindSummaryResponse.builder()
                .focusMinutes(focusMinutes)
                .tasksCompleted(tasksCompleted)
                .workouts(workouts)
                .learnings(learnings)
                .streakDays(streakDays)
                .captured(captured)
                .converted(converted)
                .reframed(reframed)
                .released(released)
                .moodScore(moodScore)
                .build();
    }

    /**
     * Consecutive days (looking back from today, or from yesterday if today is still empty)
     * on which the user showed up here — captured at least one entry.
     */
    private long computeStreak(String userId, LocalDate today) {
        java.util.Set<LocalDate> activeDays = mindEntryRepository.findByUserId(userId).stream()
                .filter(e -> "BREATH".equalsIgnoreCase(e.getType()))
                .map(MindEntry::getDate)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (activeDays.isEmpty()) {
            return 0;
        }
        LocalDate cursor = today;
        if (!activeDays.contains(cursor)) {
            cursor = cursor.minusDays(1);
            if (!activeDays.contains(cursor)) {
                return 0;
            }
        }
        long streak = 0;
        while (activeDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private MindEntry requireEntry(String id) {
        String userId = UserContext.getRequiredUserId();
        return mindEntryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Mind entry not found with id: " + id));
    }

    private Instant instantAtStart(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    private Instant instantAtEnd(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
