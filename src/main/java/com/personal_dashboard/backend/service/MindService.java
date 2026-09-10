package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.LoopRadarDay;
import com.personal_dashboard.backend.dto.MindSummaryResponse;
import com.personal_dashboard.backend.dto.WorryLedgerResponse;
import com.personal_dashboard.backend.dto.request.DailyLogRequest;
import com.personal_dashboard.backend.dto.request.DailyTaskRequest;
import com.personal_dashboard.backend.dto.request.MindEntryRequest;
import com.personal_dashboard.backend.dto.request.MindLaneRequest;
import com.personal_dashboard.backend.dto.request.MindMoodRequest;
import com.personal_dashboard.backend.dto.request.MindNoticedRequest;
import com.personal_dashboard.backend.dto.request.MindPredictionRequest;
import com.personal_dashboard.backend.dto.request.MindSpiralRequest;
import com.personal_dashboard.backend.dto.request.MindStatusRequest;
import com.personal_dashboard.backend.dto.request.MindVerdictRequest;
import com.personal_dashboard.backend.model.DailyLog;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.FocusSessionStatus;
import com.personal_dashboard.backend.model.IntrusiveMeta;
import com.personal_dashboard.backend.model.Learning;
import com.personal_dashboard.backend.model.MindEntry;
import com.personal_dashboard.backend.model.SleepLog;
import com.personal_dashboard.backend.model.SpiralLog;
import com.personal_dashboard.backend.model.StravaActivity;
import com.personal_dashboard.backend.model.WorryPrediction;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.FocusSessionRepository;
import com.personal_dashboard.backend.repository.LearningRepository;
import com.personal_dashboard.backend.repository.MindEntryRepository;
import com.personal_dashboard.backend.repository.SleepLogRepository;
import com.personal_dashboard.backend.repository.StravaActivityRepository;
import com.personal_dashboard.backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class MindService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final int EVIDENCE_WINDOW_DAYS = 7;
    private static final int MAX_TASK_TITLE_LENGTH = 140;
    private static final int STREAK_LOOKBACK_DAYS = 400;
    private static final int DEFAULT_RADAR_DAYS = 30;
    private static final int MAX_RADAR_DAYS = 90;

    private static final String LANE_PROBLEM = "PROBLEM";
    private static final String LANE_WORRY = "WORRY";
    private static final String LANE_INTRUSIVE = "INTRUSIVE";
    private static final Set<String> VALID_LANES = Set.of(LANE_PROBLEM, LANE_WORRY, LANE_INTRUSIVE);
    private static final Set<String> VALID_OUTCOMES = Set.of("NOT_HAPPENED", "PARTLY", "HAPPENED");
    private static final Set<String> VALID_SEVERITIES = Set.of("BETTER", "AS_FEARED", "WORSE");
    private static final Set<String> VALID_INTRUSIVE_CATEGORIES = Set.of("DOUBT", "HARM", "IMMORAL", "UNNAMED");

    private final MindEntryRepository mindEntryRepository;
    private final DailyTaskService dailyTaskService;
    private final DailyLogService dailyLogService;
    private final DailyTaskRepository dailyTaskRepository;
    private final FocusSessionRepository focusSessionRepository;
    private final LearningRepository learningRepository;
    private final StravaActivityRepository stravaActivityRepository;
    private final SleepLogRepository sleepLogRepository;

    // ---------- Reads ----------

    public List<MindEntry> getEntries(String type, String status) {
        String userId = UserContext.getRequiredUserId();
        resurfaceDueParked(userId);

        List<MindEntry> entries = mindEntryRepository.findByUserId(userId);
        return entries.stream()
                .filter(e -> type == null || type.equalsIgnoreCase(e.getType()))
                .filter(e -> status == null || status.equalsIgnoreCase(e.getStatus()))
                .sorted(Comparator.comparing(MindEntry::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::redactSealed)
                .toList();
    }

    /**
     * The sealed archive — the only path that returns sealed text, and only when the user
     * has deliberately asked for it.
     */
    public List<MindEntry> getSealedEntries() {
        String userId = UserContext.getRequiredUserId();
        return mindEntryRepository.findByUserId(userId).stream()
                .filter(e -> Boolean.TRUE.equals(e.getTextSealed()))
                .sorted(Comparator.comparing(MindEntry::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Strips the text off a sealed entry on its way out.
     *
     * Returns a copy rather than mutating, so a redacted object can never be handed back
     * to save() and silently wipe the stored text. Enforced here rather than at each call
     * site because "every future caller remembers to redact" is not a property that holds.
     */
    private MindEntry redactSealed(MindEntry entry) {
        if (!Boolean.TRUE.equals(entry.getTextSealed()) || entry.getText() == null) {
            return entry;
        }
        return entry.toBuilder().text(null).build();
    }

    /**
     * Any parked worry whose review date has arrived comes back — the "still true? still
     * yours to carry?" moment. Occurs lazily on read so no scheduler is needed.
     *
     * A worry carrying an unanswered prediction resurfaces as VERDICT_DUE instead of OPEN,
     * and keeps its reviewDate so the verdict prompt can say when it was parked. That
     * prompt is the entire point of the ledger: without it, predictions accumulate and are
     * never disconfirmed, which is the state the user is already in without the app.
     */
    private void resurfaceDueParked(String userId) {
        List<MindEntry> due = mindEntryRepository.findDueParked(userId, LocalDate.now(ZONE));
        if (due.isEmpty()) {
            return;
        }
        due.forEach(e -> {
            if (awaitsVerdict(e)) {
                e.setStatus("VERDICT_DUE");
            } else {
                e.setStatus("OPEN");
                e.setReviewDate(null);
            }
        });
        mindEntryRepository.saveAll(due);
        log.info("Resurfaced {} parked worries for user {}", due.size(), userId);
    }

    private boolean awaitsVerdict(MindEntry entry) {
        WorryPrediction prediction = entry.getPrediction();
        return prediction != null && prediction.getOutcome() == null;
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
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .outcome(normalizeOutcome(request.getOutcome()))
                .date(date)
                .build();
        MindEntry saved = mindEntryRepository.save(entry);
        // Entry content is never logged — mind entries are private mental-health data.
        log.info("Created mind entry {} (type={}) for {}", saved.getId(), type, date);
        return saved;
    }

    public MindEntry updateEntry(String id, MindEntryRequest request) {
        log.info("Updating mind entry {}", id);
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
        if (request.getNote() != null) {
            entry.setNote(request.getNote().isBlank() ? null : request.getNote().trim());
        }
        if (request.getOutcome() != null) {
            entry.setOutcome(normalizeOutcome(request.getOutcome()));
        }
        return mindEntryRepository.save(entry);
    }

    /** "" / blank → null; otherwise the trimmed, upper-cased outcome token. */
    private String normalizeOutcome(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase();
    }

    public MindEntry updateStatus(String id, MindStatusRequest request) {
        MindEntry entry = requireEntry(id);
        String status = request.getStatus().toUpperCase();
        log.info("Mind entry {} status: {} -> {}", id, entry.getStatus(), status);
        entry.setStatus(status);

        switch (status) {
            case "PARKED" -> {
                entry.setReviewDate(
                        request.getReviewDate() != null ? LocalDate.parse(request.getReviewDate()) : null);
                entry.setWasParked(true);
            }
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
        log.info("Converting mind entry {} to a task", id);
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
        log.info("Linked mind entry {} to new task {}", id, task.getId());

        entry.setStatus("CONVERTED");
        entry.setLinkedTaskId(task.getId());
        entry.setResolvedAt(Instant.now());
        return mindEntryRepository.save(entry);
    }

    /**
     * Log an intrusive thought as noticed and let pass.
     *
     * The lane exists because a worry and an intrusive thought need opposite handling. A
     * worry rewards examination; an intrusive thought is fed by it. So this path offers no
     * reframe, no park, no follow-up — it records that the thought was seen, seals any text
     * the user chose to type, and closes immediately. The entry lands terminal (NOTICED)
     * rather than sitting in the inbox waiting to be re-read.
     */
    public MindEntry noticed(MindNoticedRequest request) {
        String userId = UserContext.getRequiredUserId();
        LocalDate date = request.getDate() != null && !request.getDate().isBlank()
                ? LocalDate.parse(request.getDate())
                : LocalDate.now(ZONE);

        String text = request.getText() != null && !request.getText().isBlank()
                ? request.getText().trim()
                : null;

        String category = request.getCategory() != null
                ? request.getCategory().toUpperCase()
                : "UNNAMED";
        if (!VALID_INTRUSIVE_CATEGORIES.contains(category)) {
            category = "UNNAMED";
        }

        IntrusiveMeta meta = IntrusiveMeta.builder()
                .category(category)
                .intensity(request.getIntensity())
                .urgeWaitedSeconds(request.getUrgeWaitedSeconds())
                .urgeFaded(request.getUrgeFaded())
                .build();

        MindEntry entry = MindEntry.builder()
                .userId(userId)
                .type("THOUGHT")
                .lane(LANE_INTRUSIVE)
                .text(text)
                .textSealed(text != null)
                .intrusive(meta)
                .status("NOTICED")
                .date(date)
                .resolvedAt(Instant.now())
                .build();

        MindEntry saved = mindEntryRepository.save(entry);
        // Category only — the text itself is never logged, sealed or not.
        log.info("Noticed intrusive thought {} (category={}) for {}", saved.getId(), category, date);
        return redactSealed(saved);
    }

    /**
     * Triage: answer "what is this?" before any action is offered on it.
     *
     * Moving an entry into the INTRUSIVE lane seals whatever text it already carries, since
     * it arrived through the ordinary capture box before the user knew which lane it
     * belonged to.
     */
    public MindEntry setLane(String id, MindLaneRequest request) {
        MindEntry entry = requireEntry(id);
        String lane = request.getLane().toUpperCase();
        if (!VALID_LANES.contains(lane)) {
            throw new IllegalArgumentException("Unknown lane: " + request.getLane());
        }
        log.info("Mind entry {} triaged into lane {}", id, lane);
        entry.setLane(lane);

        if (LANE_INTRUSIVE.equals(lane) && entry.getText() != null && !entry.getText().isBlank()) {
            entry.setTextSealed(true);
        }
        return redactSealed(mindEntryRepository.save(entry));
    }

    /**
     * Attach a prediction to a worry: what is feared, and how likely it feels right now.
     * Recorded at park time, when the feeling is live — asking afterwards would collect a
     * memory of the fear rather than the fear itself, and memory revises itself downward.
     */
    public MindEntry savePrediction(String id, MindPredictionRequest request) {
        MindEntry entry = requireEntry(id);
        log.info("Recording prediction on mind entry {} ({}%)", id, request.getPredictedProbability());

        WorryPrediction prediction = entry.getPrediction() != null
                ? entry.getPrediction()
                : WorryPrediction.builder().build();
        if (request.getFearedOutcome() != null) {
            prediction.setFearedOutcome(request.getFearedOutcome().trim());
        }
        if (request.getPredictedProbability() != null) {
            prediction.setPredictedProbability(request.getPredictedProbability());
        }
        entry.setPrediction(prediction);
        if (entry.getLane() == null) {
            entry.setLane(LANE_WORRY);
        }
        return redactSealed(mindEntryRepository.save(entry));
    }

    /**
     * Record what actually happened. This is the payload of the whole ledger — one more
     * row in the user's own evidence that the thing they were certain about did not arrive.
     */
    public MindEntry saveVerdict(String id, MindVerdictRequest request) {
        MindEntry entry = requireEntry(id);
        String outcome = request.getOutcome().toUpperCase();
        if (!VALID_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("Unknown outcome: " + request.getOutcome());
        }
        String severity = request.getSeverity() != null ? request.getSeverity().toUpperCase() : null;
        if (severity != null && !VALID_SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("Unknown severity: " + request.getSeverity());
        }
        if ("NOT_HAPPENED".equals(outcome)) {
            severity = null;
        }

        WorryPrediction prediction = entry.getPrediction() != null
                ? entry.getPrediction()
                : WorryPrediction.builder().build();
        prediction.setOutcome(outcome);
        prediction.setSeverity(severity);
        prediction.setRecordedAt(Instant.now());
        entry.setPrediction(prediction);

        log.info("Verdict on mind entry {}: {} ({})", id, outcome, severity);

        // The worry is answered either way, so it leaves the parking lot for good.
        entry.setStatus("RESOLVED");
        entry.setReviewDate(null);
        entry.setResolvedAt(Instant.now());
        return redactSealed(mindEntryRepository.save(entry));
    }

    /**
     * Record a Spiral Breaker run. Completed or abandoned alike — an abandoned run is still
     * a moment the user noticed they were spiralling, and nothing here should read as a
     * test they can fail.
     */
    public MindEntry logSpiral(MindSpiralRequest request) {
        String userId = UserContext.getRequiredUserId();
        LocalDate date = request.getDate() != null && !request.getDate().isBlank()
                ? LocalDate.parse(request.getDate())
                : LocalDate.now(ZONE);

        SpiralLog spiral = SpiralLog.builder()
                .solvableIn24h(request.getSolvableIn24h())
                .returnedToTaskId(request.getReturnedToTaskId())
                .durationSeconds(request.getDurationSeconds())
                .build();

        MindEntry entry = MindEntry.builder()
                .userId(userId)
                .type("SPIRAL")
                .status("RESOLVED")
                .spiral(spiral)
                .date(date)
                .resolvedAt(Instant.now())
                .build();

        MindEntry saved = mindEntryRepository.save(entry);
        log.info("Logged spiral breaker session {} for {} ({}s)", saved.getId(), date, request.getDurationSeconds());
        return saved;
    }

    public void deleteEntry(String id) {
        log.info("Deleting mind entry {}", id);
        MindEntry entry = requireEntry(id);
        mindEntryRepository.delete(entry);
    }

    public DailyLog saveMood(LocalDate date, MindMoodRequest request) {
        log.info("Saving mood check-in for {} (score={})", date, request.getMoodScore());
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

    // ---------- Worry ledger ----------

    /**
     * The accumulating case against the user's own catastrophising.
     *
     * Rates stay null until at least one verdict exists, so the UI can show an honest
     * empty state instead of a fabricated 0% — a made-up reassuring number would poison
     * the one thing this instrument has going for it, which is that the user trusts it
     * because they wrote every row themselves.
     */
    public WorryLedgerResponse getWorryLedger() {
        String userId = UserContext.getRequiredUserId();
        List<MindEntry> predicted = mindEntryRepository.findByUserId(userId).stream()
                .filter(e -> e.getPrediction() != null)
                .toList();

        List<WorryPrediction> resolved = predicted.stream()
                .map(MindEntry::getPrediction)
                .filter(pr -> pr.getOutcome() != null)
                .toList();

        long notHappened = resolved.stream().filter(pr -> "NOT_HAPPENED".equals(pr.getOutcome())).count();
        long partly = resolved.stream().filter(pr -> "PARTLY".equals(pr.getOutcome())).count();
        long happened = resolved.stream().filter(pr -> "HAPPENED".equals(pr.getOutcome())).count();

        java.util.OptionalDouble avgPredicted = predicted.stream()
                .map(e -> e.getPrediction().getPredictedProbability())
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average();
        Double meanPredicted = avgPredicted.isPresent() ? avgPredicted.getAsDouble() : null;

        // PARTLY counts as half. Calling a partial hit "nothing" would overstate the case,
        // and an instrument that flatters the user is one they will stop believing.
        Double actualRate = resolved.isEmpty()
                ? null
                : ((happened + (partly / 2.0)) / resolved.size()) * 100.0;

        return WorryLedgerResponse.builder()
                .totalPredicted(predicted.size())
                .totalResolved(resolved.size())
                .notHappened(notHappened)
                .partly(partly)
                .happened(happened)
                .meanPredictedProbability(meanPredicted)
                .actualOccurrenceRate(actualRate)
                .copedBetter(resolved.stream().filter(pr -> "BETTER".equals(pr.getSeverity())).count())
                .copedAsFeared(resolved.stream().filter(pr -> "AS_FEARED".equals(pr.getSeverity())).count())
                .copedWorse(resolved.stream().filter(pr -> "WORSE".equals(pr.getSeverity())).count())
                .build();
    }

    // ---------- Loop radar ----------

    /**
     * A flat per-day series of mind activity next to the Life OS signals that plausibly
     * move it. Deliberately asserts nothing: every rule that reads a pattern out of this
     * lives client-side in lib/insights/mind.ts, deterministic and inspectable, matching
     * how the rest of the app's insights work.
     */
    public List<LoopRadarDay> getLoopRadar(Integer days) {
        String userId = UserContext.getRequiredUserId();
        int window = days != null ? Math.min(Math.max(days, 1), MAX_RADAR_DAYS) : DEFAULT_RADAR_DAYS;
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = today.minusDays(window - 1L);

        Map<LocalDate, long[]> mindCounts = new HashMap<>();
        // [problems, worries, intrusive, untriaged, spirals]
        for (MindEntry entry : mindEntryRepository.findByUserIdAndDateRange(userId, start, today)) {
            if (entry.getDate() == null) {
                continue;
            }
            long[] row = mindCounts.computeIfAbsent(entry.getDate(), d -> new long[5]);
            if ("SPIRAL".equalsIgnoreCase(entry.getType())) {
                row[4]++;
                continue;
            }
            if (!"THOUGHT".equalsIgnoreCase(entry.getType())) {
                continue;
            }
            String lane = entry.getLane();
            if (LANE_PROBLEM.equals(lane)) {
                row[0]++;
            } else if (LANE_WORRY.equals(lane)) {
                row[1]++;
            } else if (LANE_INTRUSIVE.equals(lane)) {
                row[2]++;
            } else {
                row[3]++;
            }
        }

        Map<LocalDate, Double> sleepHours = new HashMap<>();
        for (SleepLog sleep : sleepLogRepository.findByUserIdAndDateRange(userId, start, today)) {
            if (sleep.getDate() != null) {
                sleepHours.put(sleep.getDate(), Math.round(sleep.getDurationMinutes() / 6.0) / 10.0);
            }
        }

        Map<LocalDate, Long> focusMinutes = new HashMap<>();
        focusSessionRepository
                .findByUserIdAndStatusAndStartTimeBetween(
                        userId, FocusSessionStatus.COMPLETED, instantAtStart(start), instantAtEnd(today))
                .forEach(session -> {
                    if (session.getStartTime() == null) {
                        return;
                    }
                    LocalDate day = session.getStartTime().atZone(ZONE).toLocalDate();
                    focusMinutes.merge(day, (long) Math.max(0, session.getDurationMinutes()), Long::sum);
                });

        Map<LocalDate, Long> tasksCompleted = new HashMap<>();
        dailyTaskRepository.findByUserIdAndDateRange(userId, start, today.plusDays(1)).stream()
                .filter(t -> Boolean.TRUE.equals(t.getCompleted()))
                .filter(t -> t.getDate() != null)
                .forEach(t -> tasksCompleted.merge(t.getDate(), 1L, Long::sum));

        Map<LocalDate, Long> workouts = new HashMap<>();
        stravaActivityRepository.findByUserIdAndDateBetween(userId, start, today).stream()
                .filter(a -> a.getDate() != null)
                .forEach(a -> workouts.merge(a.getDate(), 1L, Long::sum));

        Map<LocalDate, Integer> moodScores = new HashMap<>();
        dailyLogService.getRange(start, today).stream()
                .filter(l -> l.getDate() != null && l.getMoodScore() != null)
                .forEach(l -> moodScores.put(l.getDate(), l.getMoodScore()));

        List<LoopRadarDay> series = new ArrayList<>(window);
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            long[] row = mindCounts.getOrDefault(day, new long[5]);
            series.add(LoopRadarDay.builder()
                    .date(day.toString())
                    .problems(row[0])
                    .worries(row[1])
                    .intrusive(row[2])
                    .untriaged(row[3])
                    .spirals(row[4])
                    .sleepHours(sleepHours.get(day))
                    .focusMinutes(focusMinutes.get(day))
                    .tasksCompleted(tasksCompleted.get(day))
                    .workouts(workouts.get(day))
                    .moodScore(moodScores.get(day))
                    .build());
        }
        return series;
    }

    /**
     * Consecutive days (looking back from today, or from yesterday if today is still empty)
     * on which the user showed up here. "Showing up" is any act of conscious self-check-in —
     * a breathing session, capturing a thought, or a mood check-in — not just the breathing tool.
     */
    private long computeStreak(String userId, LocalDate today) {
        java.util.Set<LocalDate> activeDays = mindEntryRepository.findByUserId(userId).stream()
                .filter(e -> "BREATH".equalsIgnoreCase(e.getType()) || "THOUGHT".equalsIgnoreCase(e.getType()))
                .map(MindEntry::getDate)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));

        dailyLogService.getRange(today.minusDays(STREAK_LOOKBACK_DAYS), today).stream()
                .filter(l -> l.getMoodScore() != null)
                .map(DailyLog::getDate)
                .filter(java.util.Objects::nonNull)
                .forEach(activeDays::add);

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
