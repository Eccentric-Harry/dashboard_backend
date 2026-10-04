package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.ProgramMediaView;
import com.personal_dashboard.backend.dto.ProgramStateResponse;
import com.personal_dashboard.backend.dto.ProgramStateResponse.RunDay;
import com.personal_dashboard.backend.dto.ProgramStateResponse.Sources;
import com.personal_dashboard.backend.dto.ProgramView;
import com.personal_dashboard.backend.dto.request.MindMoodRequest;
import com.personal_dashboard.backend.dto.request.ProgramAssessmentRequest;
import com.personal_dashboard.backend.dto.request.ProgramLetterRequest;
import com.personal_dashboard.backend.dto.request.ProgramLogRequest;
import com.personal_dashboard.backend.dto.request.ProgramMediaRequest;
import com.personal_dashboard.backend.dto.request.ProgramReviewRequest;
import com.personal_dashboard.backend.dto.request.ProgramSettingsRequest;
import com.personal_dashboard.backend.dto.request.ProgramStartRequest;
import com.personal_dashboard.backend.model.DailyFoodLog;
import com.personal_dashboard.backend.model.DailyLog;
import com.personal_dashboard.backend.model.FocusSession;
import com.personal_dashboard.backend.model.FocusSessionStatus;
import com.personal_dashboard.backend.model.Program;
import com.personal_dashboard.backend.model.ProgramAssessment;
import com.personal_dashboard.backend.model.ProgramLog;
import com.personal_dashboard.backend.model.ProgramMedia;
import com.personal_dashboard.backend.model.ProgramReview;
import com.personal_dashboard.backend.model.SleepLog;
import com.personal_dashboard.backend.model.StravaActivity;
import com.personal_dashboard.backend.model.UserAccount;
import com.personal_dashboard.backend.repository.DailyLogRepository;
import com.personal_dashboard.backend.repository.FocusSessionRepository;
import com.personal_dashboard.backend.repository.ProgramAssessmentRepository;
import com.personal_dashboard.backend.repository.ProgramLogRepository;
import com.personal_dashboard.backend.repository.ProgramMediaRepository;
import com.personal_dashboard.backend.repository.ProgramRepository;
import com.personal_dashboard.backend.repository.ProgramReviewRepository;
import com.personal_dashboard.backend.repository.SleepLogRepository;
import com.personal_dashboard.backend.repository.StravaActivityRepository;
import com.personal_dashboard.backend.repository.UserAccountRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.util.ParallelReads;
import com.personal_dashboard.backend.util.ProgramScoring;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Programs on /goals — "90 days to 23" (design/LIGHTHOUSE_90_PLAN.md). This service stores
 * and validates; the client derives every view from {@link #getState}. Two rules are kept
 * here because the server must own them:
 * <ul>
 *   <li>targets change only through {@link #saveReview} — settings can't touch them;</li>
 *   <li>a sealed letter's text never leaves the server before the day it opens.</li>
 * </ul>
 * A mood check-in is written through to /mind (DailyLog.moodScore), so mood is logged once
 * across Life OS; /mind's own check-ins come back as a source.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgramService {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    static final int DEFAULT_DAYS = 90;
    /** Logs may sit a week either side of the program (audit context, a late entry). */
    static final int LOG_MARGIN_DAYS = 7;
    static final int MAX_MEDIA = 120;
    static final int MAX_PHOTO_BYTES = 1_500_000;
    static final int MAX_AUDIO_BYTES = 4_000_000;
    /** The day rolls over at 04:00, like the camp's (a 1am focus session belongs to the evening). */
    static final Duration ROLLOVER = Duration.ofHours(4);

    static final Set<String> LETTER_KEYS = Set.of("to23", "from23", "to24");
    private static final Set<String> PHOTO_MIMES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> AUDIO_MIMES = Set.of("audio/webm", "audio/mp4", "audio/ogg", "audio/mpeg", "audio/aac", "audio/x-m4a", "audio/wav");
    private static final Pattern DATA_URL = Pattern.compile("^data:([a-z]+/[a-z0-9.+-]+)(?:;[^,;]*)*;base64,([A-Za-z0-9+/=\\s]+)$");
    private static final Set<String> RUN_SPORTS = Set.of("run", "trailrun", "virtualrun");

    private final ProgramRepository programRepository;
    private final ProgramLogRepository logRepository;
    private final ProgramReviewRepository reviewRepository;
    private final ProgramAssessmentRepository assessmentRepository;
    private final ProgramMediaRepository mediaRepository;
    private final SleepLogRepository sleepLogRepository;
    private final DailyLogRepository dailyLogRepository;
    private final StravaActivityRepository stravaActivityRepository;
    private final FocusSessionRepository focusSessionRepository;
    private final UserAccountRepository userAccountRepository;
    private final MindService mindService;
    private final MongoTemplate mongoTemplate;

    // ── Reading ─────────────────────────────────────────────────────────

    /** The active program with everything on it, plus the other routes' data for its days. */
    public ProgramStateResponse getState(LocalDate today) {
        String userId = UserContext.getRequiredUserId();
        LocalDate day = today != null ? today : LocalDate.now(ZONE);
        Program program = programRepository.findFirstByUserIdAndStatusOrderByStartDateDesc(userId, Program.ACTIVE).orElse(null);
        if (program == null) {
            Sources sources = Sources.builder()
                    .protein(Map.of()).sleep(Map.of()).mood(Map.of()).runs(Map.of()).focus(Map.of())
                    .profileWeightKg(profileWeight(userAccountRepository.findById(userId).orElse(null)))
                    .build();
            return ProgramStateResponse.builder()
                    .program(null).logs(List.of()).reviews(List.of()).assessments(List.of()).media(List.of())
                    .sources(sources)
                    .build();
        }

        String programId = program.getId();
        LocalDate from = program.getStartDate().minusDays(LOG_MARGIN_DAYS);
        LocalDate to = day.isAfter(program.getEndDate()) ? program.getEndDate() : day;

        var logsRead = ParallelReads.fork(() -> logRepository.findByUserIdAndProgramIdOrderByDateAscCreatedAtAsc(userId, programId));
        var reviewsRead = ParallelReads.fork(() -> reviewRepository.findByUserIdAndProgramIdOrderByWeekStartAsc(userId, programId));
        var assessmentsRead = ParallelReads.fork(() -> assessmentRepository.findByUserIdAndProgramIdOrderByDateAscCreatedAtAsc(userId, programId));
        var mediaRead = ParallelReads.fork(() -> listMedia(userId, programId));
        var sourcesRead = readSources(userId, from, to);

        return ProgramStateResponse.builder()
                .program(view(program))
                .logs(ParallelReads.join(logsRead))
                .reviews(ParallelReads.join(reviewsRead))
                .assessments(ParallelReads.join(assessmentsRead))
                .media(ParallelReads.join(mediaRead))
                .sources(ParallelReads.join(sourcesRead))
                .build();
    }

    // ── The program itself ──────────────────────────────────────────────

    public ProgramView start(ProgramStartRequest request) {
        String userId = UserContext.getRequiredUserId();
        if (programRepository.findFirstByUserIdAndStatusOrderByStartDateDesc(userId, Program.ACTIVE).isPresent()) {
            throw new IllegalArgumentException("A program is already running.");
        }
        LocalDate start = LocalDate.parse(request.getStartDate());
        LocalDate end = blank(request.getEndDate()) ? start.plusDays(DEFAULT_DAYS - 1L) : LocalDate.parse(request.getEndDate());
        if (end.isBefore(start.plusDays(13)) || end.isAfter(start.plusDays(365))) {
            throw new IllegalArgumentException("A program runs between 2 weeks and a year.");
        }
        LocalDate birthday = blank(request.getBirthday()) ? end : LocalDate.parse(request.getBirthday());
        Double weight = request.getWeightKg() != null ? request.getWeightKg()
                : profileWeight(userAccountRepository.findById(userId).orElse(null));

        Instant now = Instant.now();
        Program program = Program.builder()
                .userId(userId)
                .title(blank(request.getTitle()) ? "Turning 23" : request.getTitle().trim())
                .status(Program.ACTIVE)
                .startDate(start)
                .endDate(end)
                .birthday(birthday)
                .weightKg(weight)
                .liftPlace(blank(request.getLiftPlace()) ? "gym" : request.getLiftPlace())
                .tracks(ProgramScoring.defaultTracks(weight))
                .answers(cleanMap(request.getAnswers()))
                .letters(new LinkedHashMap<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return view(programRepository.save(program));
    }

    /** Everything except targets (see {@link #saveReview}). */
    public ProgramView updateSettings(String programId, ProgramSettingsRequest request) {
        Program program = owned(programId);
        if (!blank(request.getTitle())) program.setTitle(request.getTitle().trim());
        if (!blank(request.getStartDate())) {
            LocalDate start = LocalDate.parse(request.getStartDate());
            long length = ChronoUnit.DAYS.between(program.getStartDate(), program.getEndDate());
            boolean birthdayWasEnd = Objects.equals(program.getBirthday(), program.getEndDate());
            program.setStartDate(start);
            program.setEndDate(start.plusDays(length));
            if (birthdayWasEnd) program.setBirthday(program.getEndDate());
        }
        if (!blank(request.getBirthday())) program.setBirthday(LocalDate.parse(request.getBirthday()));
        if (request.getWeightKg() != null) program.setWeightKg(request.getWeightKg());
        if (!blank(request.getLiftPlace())) program.setLiftPlace(request.getLiftPlace());
        if (request.getAnswers() != null) {
            Map<String, String> answers = new LinkedHashMap<>(program.getAnswers() == null ? Map.of() : program.getAnswers());
            request.getAnswers().forEach((k, v) -> {
                if (blank(v)) answers.remove(k);
                else answers.put(k, v.trim());
            });
            program.setAnswers(answers);
        }
        if (request.getPlans() != null) {
            request.getPlans().forEach((key, plan) -> {
                Program.Track track = track(program, key);
                track.setPlan(blank(plan) ? null : plan.trim());
            });
        }
        if (request.getOpens() != null) {
            request.getOpens().forEach((key, date) -> {
                Program.Track track = track(program, key);
                if (blank(date)) {
                    track.setOpenedOn(null);
                    return;
                }
                LocalDate on = LocalDate.parse(date);
                if (on.isAfter(program.getEndDate())) throw new IllegalArgumentException("A track can't start after the program ends");
                track.setOpenedOn(on.isBefore(program.getStartDate()) ? program.getStartDate() : on);
            });
        }
        program.setUpdatedAt(Instant.now());
        return view(programRepository.save(program));
    }

    /** Starts over: the program and everything logged on it. */
    public void delete(String programId) {
        Program program = owned(programId);
        String userId = program.getUserId();
        logRepository.deleteByUserIdAndProgramId(userId, programId);
        reviewRepository.deleteByUserIdAndProgramId(userId, programId);
        assessmentRepository.deleteByUserIdAndProgramId(userId, programId);
        mediaRepository.deleteByUserIdAndProgramId(userId, programId);
        programRepository.delete(program);
    }

    /**
     * Writes one of the letters. to23 seals until the birthday and can't be rewritten once
     * it has opened; to24 seals for a year after it; from23 (written as the future self) is
     * always readable — it's for the hard days in between.
     */
    public ProgramView writeLetter(String programId, String key, ProgramLetterRequest request) {
        if (!LETTER_KEYS.contains(key)) throw new IllegalArgumentException("Unknown letter: " + key);
        Program program = owned(programId);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate birthday = program.getBirthday() != null ? program.getBirthday() : program.getEndDate();
        LocalDate opensOn = switch (key) {
            case "to23" -> birthday;
            case "to24" -> birthday.plusYears(1);
            default -> null;
        };
        if ("to23".equals(key) && !today.isBefore(birthday)) {
            throw new IllegalArgumentException("That letter has already opened.");
        }
        Map<String, Program.Letter> letters = new LinkedHashMap<>(program.getLetters() == null ? Map.of() : program.getLetters());
        letters.put(key, Program.Letter.builder().text(request.getText().trim()).writtenAt(Instant.now()).opensOn(opensOn).build());
        program.setLetters(letters);
        program.setUpdatedAt(Instant.now());
        return view(programRepository.save(program));
    }

    // ── Logs ────────────────────────────────────────────────────────────

    public ProgramLog addLog(String programId, ProgramLogRequest request) {
        Program program = owned(programId);
        Instant now = Instant.now();
        ProgramLog log = ProgramLog.builder()
                .userId(program.getUserId())
                .programId(programId)
                .createdAt(now)
                .build();
        fill(log, program, request);
        log.setUpdatedAt(now);
        ProgramLog saved = logRepository.save(log);
        writeMoodThrough(saved);
        return saved;
    }

    public ProgramLog updateLog(String programId, String logId, ProgramLogRequest request) {
        Program program = owned(programId);
        ProgramLog log = logRepository.findByIdAndUserIdAndProgramId(logId, program.getUserId(), programId)
                .orElseThrow(() -> new IllegalArgumentException("Log not found with id: " + logId));
        if (!log.getTrack().equals(request.getTrack())) {
            throw new IllegalArgumentException("A log can't move to another track.");
        }
        fill(log, program, request);
        log.setUpdatedAt(Instant.now());
        ProgramLog saved = logRepository.save(log);
        writeMoodThrough(saved);
        return saved;
    }

    public void deleteLog(String programId, String logId) {
        Program program = owned(programId);
        ProgramLog log = logRepository.findByIdAndUserIdAndProgramId(logId, program.getUserId(), programId)
                .orElseThrow(() -> new IllegalArgumentException("Log not found with id: " + logId));
        // A mood check-in stays on /mind: that page owns its history.
        logRepository.delete(log);
    }

    private void fill(ProgramLog log, Program program, ProgramLogRequest r) {
        LocalDate date = LocalDate.parse(r.getDate());
        if (date.isBefore(program.getStartDate().minusDays(LOG_MARGIN_DAYS)) || date.isAfter(program.getEndDate().plusDays(LOG_MARGIN_DAYS))) {
            throw new IllegalArgumentException("That day is outside the program.");
        }
        String track = r.getTrack();
        Double value = r.getValue();
        switch (track) {
            case "mood" -> {
                if (value == null || value < 1 || value > 5 || value % 1 != 0) {
                    throw new IllegalArgumentException("A mood check-in is a score from 1 to 5.");
                }
            }
            case "screen" -> {
                if (value != null && value > 1440) throw new IllegalArgumentException("A day has 1440 minutes.");
                if (value == null && !Boolean.TRUE.equals(r.getUrge()) && r.getMorningRule() == null && r.getNightRule() == null) {
                    throw new IllegalArgumentException("Log the minutes, a rule, or an urge ridden out.");
                }
            }
            case "protein" -> {
                if (value != null && value > 500) throw new IllegalArgumentException("That's more protein than a day holds.");
            }
            case "regard" -> {
                if (blank(r.getText())) throw new IllegalArgumentException("Write the promise you kept.");
            }
            default -> {
                // Session tracks take any of their optional details.
            }
        }
        log.setTrack(track);
        log.setDate(date);
        log.setLevel(blank(r.getLevel()) ? null : r.getLevel());
        log.setValue(value);
        log.setMinutes(r.getMinutes());
        log.setDistanceKm(r.getDistanceKm());
        log.setFeel(r.getFeel());
        log.setSets(r.getSets() == null ? null : r.getSets().stream()
                .map(s -> ProgramLog.LiftSet.builder().exercise(s.getExercise().trim()).weightKg(s.getWeightKg()).reps(s.getReps()).build())
                .toList());
        log.setTag(trimOrNull(r.getTag()));
        log.setNote(trimOrNull(r.getNote()));
        log.setText(trimOrNull(r.getText()));
        log.setKind(trimOrNull(r.getKind()));
        log.setPursuitId(trimOrNull(r.getPursuitId()));
        log.setSession(blank(r.getSession()) ? null : r.getSession());
        log.setUrge(r.getUrge());
        log.setMorningRule(r.getMorningRule());
        log.setNightRule(r.getNightRule());
        log.setStretch(r.getStretch());
    }

    /** Mood is logged once across Life OS: a check-in here is also /mind's for that day. */
    private void writeMoodThrough(ProgramLog log) {
        if (!"mood".equals(log.getTrack()) || log.getValue() == null) return;
        String note = blank(log.getNote()) ? log.getTag() : (blank(log.getTag()) ? log.getNote() : log.getTag() + " — " + log.getNote());
        try {
            mindService.saveMood(log.getDate(), MindMoodRequest.builder()
                    .moodScore(log.getValue().intValue())
                    .moodNote(note)
                    .build());
        } catch (RuntimeException e) {
            // The program keeps its own copy; /mind catches up on the next check-in.
            ProgramService.log.warn("Mood write-through to /mind failed for {}: {}", log.getDate(), e.getMessage());
        }
    }

    // ── The weekly review — the only door targets come through ─────────

    public ReviewResult saveReview(String programId, LocalDate weekStart, ProgramReviewRequest request) {
        if (weekStart.getDayOfWeek().getValue() != 1) throw new IllegalArgumentException("A review week starts on a Monday.");
        Program program = owned(programId);
        String userId = program.getUserId();
        Instant now = Instant.now();
        ProgramReview review = reviewRepository.findByUserIdAndProgramIdAndWeekStart(userId, programId, weekStart)
                .orElseGet(() -> ProgramReview.builder()
                        .userId(userId)
                        .programId(programId)
                        .weekStart(weekStart)
                        .createdAt(now)
                        .changes(new ArrayList<>())
                        .build());

        // Keep the week's first "from" so saving twice still shows where the week started.
        Map<String, ProgramReview.TargetChange> changes = new LinkedHashMap<>();
        if (review.getChanges() != null) review.getChanges().forEach(c -> changes.put(c.getTrack(), c));
        if (request.getTargets() != null) {
            request.getTargets().forEach((key, edit) -> {
                Program.Track track = track(program, key);
                Double fromTarget = track.getTarget();
                Double fromFloor = track.getFloor();
                applyTarget(track, edit);
                if (Objects.equals(fromTarget, track.getTarget()) && Objects.equals(fromFloor, track.getFloor())) return;
                ProgramReview.TargetChange earlier = changes.get(key);
                changes.put(key, ProgramReview.TargetChange.builder()
                        .track(key)
                        .fromTarget(earlier != null ? earlier.getFromTarget() : fromTarget)
                        .fromFloor(earlier != null ? earlier.getFromFloor() : fromFloor)
                        .toTarget(track.getTarget())
                        .toFloor(track.getFloor())
                        .build());
            });
            program.setUpdatedAt(now);
            programRepository.save(program);
        }

        review.setSelfTrust(request.getSelfTrust());
        review.setWin(trimOrNull(request.getWin()));
        review.setObstacle(trimOrNull(request.getObstacle()));
        review.setAdjustment(trimOrNull(request.getAdjustment()));
        review.setIfThen(trimOrNull(request.getIfThen()));
        review.setChanges(new ArrayList<>(changes.values().stream()
                .filter(c -> !Objects.equals(c.getFromTarget(), c.getToTarget()) || !Objects.equals(c.getFromFloor(), c.getToFloor()))
                .toList()));
        review.setUpdatedAt(now);
        return new ReviewResult(reviewRepository.save(review), view(program));
    }

    /** Ranges are generous on purpose — lowering a target in a review is always allowed. */
    static void applyTarget(Program.Track track, ProgramReviewRequest.TargetEdit edit) {
        String key = track.getKey();
        if ("screen".equals(key) && Boolean.TRUE.equals(edit.getAuto())) {
            track.setTarget(null);
            return;
        }
        Double target = edit.getTarget();
        if (target == null) throw new IllegalArgumentException("A new target needs a number.");
        switch (key) {
            case "run", "lift", "learn" -> {
                long t = Math.round(target);
                if (t < 1 || t > 7) throw new IllegalArgumentException("Between 1 and 7 a week.");
                track.setTarget((double) t);
            }
            case "regard" -> {
                long t = Math.round(target);
                if (t < 1 || t > 5) throw new IllegalArgumentException("Between 1 and 5 a day.");
                track.setTarget((double) t);
            }
            case "protein", "english" -> {
                double lo = "protein".equals(key) ? 30 : 1;
                double hi = "protein".equals(key) ? 400 : 180;
                long t = Math.round(target);
                if (t < lo || t > hi) throw new IllegalArgumentException("protein".equals(key) ? "Between 30 and 400 g." : "Between 1 and 180 minutes.");
                track.setTarget((double) t);
                Double floor = edit.getFloor() != null ? Double.valueOf(Math.round(edit.getFloor())) : track.getFloor();
                if (floor != null) track.setFloor(Math.min(floor, (double) t));
            }
            case "screen" -> {
                long t = Math.round(target);
                if (t < 15 || t > 1440) throw new IllegalArgumentException("A cap between 15 minutes and a whole day.");
                track.setTarget((double) t);
            }
            default -> throw new IllegalArgumentException("That track has no target.");
        }
    }

    public record ReviewResult(ProgramReview review, ProgramView program) {
    }

    // ── Assessments ─────────────────────────────────────────────────────

    public ProgramAssessment addAssessment(String programId, ProgramAssessmentRequest request) {
        Program program = owned(programId);
        String type = request.getType();
        Integer score = switch (type) {
            case ProgramAssessment.ROSENBERG -> ProgramScoring.rosenberg(request.getAnswers());
            case ProgramAssessment.WHO5 -> ProgramScoring.who5(request.getAnswers());
            default -> null;
        };
        List<String> mediaIds = null;
        if (ProgramAssessment.BODY.equals(type)) {
            if (request.getWeightKg() == null && request.getWaistCm() == null
                    && (request.getMediaIds() == null || request.getMediaIds().isEmpty())) {
                throw new IllegalArgumentException("A body check needs a photo, a weight or a waist.");
            }
            mediaIds = request.getMediaIds() == null ? List.of() : request.getMediaIds().stream()
                    .filter(id -> mediaRepository.findByIdAndUserIdAndProgramId(id, program.getUserId(), programId).isPresent())
                    .toList();
        }
        ProgramAssessment assessment = ProgramAssessment.builder()
                .userId(program.getUserId())
                .programId(programId)
                .date(LocalDate.parse(request.getDate()))
                .type(type)
                .answers(ProgramAssessment.BODY.equals(type) ? null : request.getAnswers())
                .score(score)
                .weightKg(ProgramAssessment.BODY.equals(type) ? request.getWeightKg() : null)
                .waistCm(ProgramAssessment.BODY.equals(type) ? request.getWaistCm() : null)
                .mediaIds(mediaIds)
                .note(trimOrNull(request.getNote()))
                .createdAt(Instant.now())
                .build();
        return assessmentRepository.save(assessment);
    }

    public void deleteAssessment(String programId, String assessmentId) {
        Program program = owned(programId);
        ProgramAssessment a = assessmentRepository.findByIdAndUserIdAndProgramId(assessmentId, program.getUserId(), programId)
                .orElseThrow(() -> new IllegalArgumentException("Check-in not found with id: " + assessmentId));
        assessmentRepository.delete(a);
    }

    // ── Media ───────────────────────────────────────────────────────────

    public ProgramMediaView addMedia(String programId, ProgramMediaRequest request) {
        Program program = owned(programId);
        if (mediaRepository.countByUserIdAndProgramId(program.getUserId(), programId) >= MAX_MEDIA) {
            throw new IllegalArgumentException("This program's album is full — delete an old one first.");
        }
        Matcher m = DATA_URL.matcher(request.getDataUrl());
        if (!m.matches()) throw new IllegalArgumentException("That file couldn't be read.");
        String mime = m.group(1).toLowerCase();
        boolean photo = ProgramMedia.PHOTO.equals(request.getKind());
        if (photo ? !PHOTO_MIMES.contains(mime) : !AUDIO_MIMES.contains(mime)) {
            throw new IllegalArgumentException(photo ? "Photos must be JPEG, PNG or WebP." : "That recording format isn't supported.");
        }
        if (photo == "speech".equals(request.getLabel())) {
            throw new IllegalArgumentException(photo ? "Photos are front or side." : "Recordings are labelled speech.");
        }
        byte[] data;
        try {
            data = Base64.getMimeDecoder().decode(m.group(2));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That file couldn't be read.");
        }
        if (data.length == 0 || data.length > (photo ? MAX_PHOTO_BYTES : MAX_AUDIO_BYTES)) {
            throw new IllegalArgumentException(photo ? "Photos must be under 1.5 MB." : "Recordings must be under 4 MB.");
        }
        ProgramMedia media = ProgramMedia.builder()
                .userId(program.getUserId())
                .programId(programId)
                .kind(request.getKind())
                .label(request.getLabel())
                .date(LocalDate.parse(request.getDate()))
                .mime(mime)
                .data(data)
                .bytes(data.length)
                .durationSec(photo ? null : request.getDurationSec())
                .createdAt(Instant.now())
                .build();
        return mediaView(mediaRepository.save(media), false);
    }

    public ProgramMediaView getMedia(String programId, String mediaId) {
        Program program = owned(programId);
        ProgramMedia media = mediaRepository.findByIdAndUserIdAndProgramId(mediaId, program.getUserId(), programId)
                .orElseThrow(() -> new IllegalArgumentException("Not found: " + mediaId));
        return mediaView(media, true);
    }

    public void deleteMedia(String programId, String mediaId) {
        Program program = owned(programId);
        ProgramMedia media = mediaRepository.findByIdAndUserIdAndProgramId(mediaId, program.getUserId(), programId)
                .orElseThrow(() -> new IllegalArgumentException("Not found: " + mediaId));
        mediaRepository.delete(media);
    }

    private List<ProgramMediaView> listMedia(String userId, String programId) {
        Query q = new Query(Criteria.where("userId").is(userId).and("programId").is(programId))
                .with(Sort.by(Sort.Direction.ASC, "date", "createdAt"));
        q.fields().exclude("data");
        return mongoTemplate.find(q, ProgramMedia.class).stream().map(m -> mediaView(m, false)).toList();
    }

    private static ProgramMediaView mediaView(ProgramMedia m, boolean withData) {
        return ProgramMediaView.builder()
                .id(m.getId())
                .kind(m.getKind())
                .label(m.getLabel())
                .date(m.getDate())
                .mime(m.getMime())
                .bytes(m.getBytes())
                .durationSec(m.getDurationSec())
                .createdAt(m.getCreatedAt())
                .dataUrl(withData && m.getData() != null ? "data:" + m.getMime() + ";base64," + Base64.getEncoder().encodeToString(m.getData()) : null)
                .build();
    }

    // ── Sources: what the rest of Life OS already knows ────────────────

    private CompletableFuture<Sources> readSources(String userId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return ParallelReads.fork(() -> Sources.builder()
                    .protein(Map.of()).sleep(Map.of()).mood(Map.of()).runs(Map.of()).focus(Map.of())
                    .profileWeightKg(profileWeight(userAccountRepository.findById(userId).orElse(null)))
                    .build());
        }
        var proteinRead = ParallelReads.fork(() -> proteinByDay(userId, from, to));
        var accountRead = ParallelReads.fork(() -> userAccountRepository.findById(userId).orElse(null));
        var sleepRead = ParallelReads.fork(() -> sleepLogRepository.findByUserIdAndDateRange(userId, from, to));
        var moodRead = ParallelReads.fork(() -> dailyLogRepository.findByUserIdAndDateRange(userId, from, to));
        // Spring Data's Between is exclusive; widen by a day and filter here.
        var runsRead = ParallelReads.fork(() -> stravaActivityRepository.findByUserIdAndDateBetween(userId, from.minusDays(1), to.plusDays(1)));
        var focusRead = ParallelReads.fork(() -> focusSessionRepository.findByUserIdAndStatusAndStartTimeBetween(
                userId, FocusSessionStatus.COMPLETED,
                from.atStartOfDay(ZONE).toInstant().plus(ROLLOVER),
                to.plusDays(1).atStartOfDay(ZONE).toInstant().plus(ROLLOVER)));

        return CompletableFuture.allOf(proteinRead, accountRead, sleepRead, moodRead, runsRead, focusRead).handle((ignored, error) -> {
            UserAccount account = ParallelReads.join(accountRead);

            Map<String, Integer> sleep = new TreeMap<>();
            for (SleepLog s : ParallelReads.join(sleepRead)) {
                if (s.getDate() != null && s.getDurationMinutes() > 0) sleep.put(s.getDate().toString(), s.getDurationMinutes());
            }

            Map<String, Integer> mood = new TreeMap<>();
            for (DailyLog d : ParallelReads.join(moodRead)) {
                if (d.getDate() != null && d.getMoodScore() != null) mood.put(d.getDate().toString(), d.getMoodScore());
            }

            Map<String, RunDay> runs = new TreeMap<>();
            for (StravaActivity a : ParallelReads.join(runsRead)) {
                if (a.getDate() == null || a.getDate().isBefore(from) || a.getDate().isAfter(to)) continue;
                String sport = a.getSportType() == null ? "" : a.getSportType().toLowerCase().replace(" ", "");
                if (!RUN_SPORTS.contains(sport)) continue;
                RunDay day = runs.computeIfAbsent(a.getDate().toString(), k -> new RunDay());
                day.setCount(day.getCount() + 1);
                day.setKm(day.getKm() + (a.getDistanceKm() == null ? 0 : a.getDistanceKm()));
                day.setMinutes(day.getMinutes() + (a.getMovingTimeMinutes() == null ? 0 : a.getMovingTimeMinutes()));
            }

            Map<String, Integer> focus = new TreeMap<>();
            for (FocusSession f : ParallelReads.join(focusRead)) {
                if (f.getStartTime() == null || f.getDurationMinutes() <= 0) continue;
                String day = f.getStartTime().minus(ROLLOVER).atZone(ZONE).toLocalDate().toString();
                focus.merge(day, f.getDurationMinutes(), Integer::sum);
            }

            return Sources.builder()
                    .protein(ParallelReads.join(proteinRead))
                    .proteinGoal(account == null ? null
                            : account.getProteinTargetOverride() != null ? account.getProteinTargetOverride() : account.getTargetProtein())
                    .sleep(sleep)
                    .mood(mood)
                    .runs(runs)
                    .focus(focus)
                    .profileWeightKg(profileWeight(account))
                    .build();
        });
    }

    /** Protein grams on days with food logged; a day with nothing logged is absent (unknown, not zero). */
    private Map<String, Integer> proteinByDay(String userId, LocalDate from, LocalDate to) {
        Query q = new Query(Criteria.where("userId").is(userId)
                .and("dateString").gte(from.toString()).lte(to.toString()));
        q.fields().include("dateString").include("dailyTotals");
        Map<String, Integer> out = new TreeMap<>();
        for (DailyFoodLog log : mongoTemplate.find(q, DailyFoodLog.class)) {
            var totals = log.getDailyTotals();
            if (log.getDateString() == null || totals == null) continue;
            int protein = totals.getTotalProteinGrams() == null ? 0 : totals.getTotalProteinGrams();
            int calories = totals.getTotalCalories() == null ? 0 : totals.getTotalCalories();
            if (protein > 0 || calories > 0) out.put(log.getDateString(), protein);
        }
        return out;
    }

    private static Double profileWeight(UserAccount account) {
        if (account == null) return null;
        if (account.getWeight() != null && account.getWeight() > 0) return account.getWeight();
        if (account.getPhysicalMetrics() != null && account.getPhysicalMetrics().getWeight() != null
                && account.getPhysicalMetrics().getWeight() > 0) {
            return account.getPhysicalMetrics().getWeight();
        }
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Program owned(String programId) {
        String userId = UserContext.getRequiredUserId();
        return programRepository.findByIdAndUserId(programId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Program not found with id: " + programId));
    }

    private static Program.Track track(Program program, String key) {
        if (program.getTracks() == null) program.setTracks(new ArrayList<>(ProgramScoring.defaultTracks(program.getWeightKg())));
        return program.getTracks().stream()
                .filter(t -> key.equals(t.getKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown track: " + key));
    }

    /** The program as the client may see it: sealed letters without their words. */
    ProgramView view(Program p) {
        LocalDate today = LocalDate.now(ZONE);
        Map<String, ProgramView.LetterView> letters = new HashMap<>();
        if (p.getLetters() != null) {
            p.getLetters().forEach((key, letter) -> {
                boolean sealed = letter.getOpensOn() != null && today.isBefore(letter.getOpensOn());
                String text = letter.getText() == null ? "" : letter.getText();
                letters.put(key, ProgramView.LetterView.builder()
                        .sealed(sealed)
                        .opensOn(letter.getOpensOn())
                        .writtenAt(letter.getWrittenAt())
                        .text(sealed ? null : text)
                        .length(text.length())
                        .build());
            });
        }
        return ProgramView.builder()
                .id(p.getId())
                .title(p.getTitle())
                .status(p.getStatus())
                .startDate(p.getStartDate())
                .endDate(p.getEndDate())
                .birthday(p.getBirthday())
                .weightKg(p.getWeightKg())
                .liftPlace(p.getLiftPlace())
                .tracks(p.getTracks())
                .answers(p.getAnswers() == null ? Map.of() : p.getAnswers())
                .letters(letters)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private static Map<String, String> cleanMap(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) in.forEach((k, v) -> {
            if (!blank(v)) out.put(k, v.trim());
        });
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
