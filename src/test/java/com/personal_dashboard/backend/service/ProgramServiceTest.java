package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.ProgramView;
import com.personal_dashboard.backend.dto.request.MindMoodRequest;
import com.personal_dashboard.backend.dto.request.ProgramLetterRequest;
import com.personal_dashboard.backend.dto.request.ProgramLogRequest;
import com.personal_dashboard.backend.dto.request.ProgramMediaRequest;
import com.personal_dashboard.backend.dto.request.ProgramReviewRequest;
import com.personal_dashboard.backend.dto.request.ProgramSettingsRequest;
import com.personal_dashboard.backend.dto.request.ProgramStartRequest;
import com.personal_dashboard.backend.model.Program;
import com.personal_dashboard.backend.model.ProgramLog;
import com.personal_dashboard.backend.model.ProgramMedia;
import com.personal_dashboard.backend.model.ProgramReview;
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
import com.personal_dashboard.backend.util.ProgramScoring;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProgramServiceTest {

    @Mock private ProgramRepository programRepository;
    @Mock private ProgramLogRepository logRepository;
    @Mock private ProgramReviewRepository reviewRepository;
    @Mock private ProgramAssessmentRepository assessmentRepository;
    @Mock private ProgramMediaRepository mediaRepository;
    @Mock private SleepLogRepository sleepLogRepository;
    @Mock private DailyLogRepository dailyLogRepository;
    @Mock private StravaActivityRepository stravaActivityRepository;
    @Mock private FocusSessionRepository focusSessionRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private MindService mindService;
    @Mock private MongoTemplate mongoTemplate;

    @InjectMocks
    private ProgramService service;

    private static final LocalDate TODAY = LocalDate.now(ProgramService.ZONE);

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Program program(LocalDate start) {
        return Program.builder()
                .id("p")
                .userId("u")
                .status(Program.ACTIVE)
                .startDate(start)
                .endDate(start.plusDays(89))
                .birthday(start.plusDays(89))
                .tracks(new ArrayList<>(ProgramScoring.defaultTracks(70.0)))
                .letters(new LinkedHashMap<>())
                .build();
    }

    private Program owned(LocalDate start) {
        Program p = program(start);
        when(programRepository.findByIdAndUserId("p", "u")).thenReturn(Optional.of(p));
        return p;
    }

    private static Program.Track track(Program p, String key) {
        return p.getTracks().stream().filter(t -> t.getKey().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void startSeedsNinetyDaysAndTheEightTracks() {
        when(programRepository.findFirstByUserIdAndStatusOrderByStartDateDesc("u", Program.ACTIVE)).thenReturn(Optional.empty());
        when(programRepository.save(any(Program.class))).thenAnswer(inv -> inv.getArgument(0));

        ProgramView view = service.start(ProgramStartRequest.builder().startDate("2026-10-05").weightKg(70.0).build());

        assertEquals(LocalDate.parse("2026-10-05"), view.getStartDate());
        assertEquals(LocalDate.parse("2027-01-02"), view.getEndDate(), "day 90 is the birthday");
        assertEquals(view.getEndDate(), view.getBirthday());
        assertEquals("Turning 23", view.getTitle());
        assertEquals(ProgramScoring.TRACKS, view.getTracks().stream().map(Program.Track::getKey).toList());
        ArgumentCaptor<Program> saved = ArgumentCaptor.forClass(Program.class);
        verify(programRepository).save(saved.capture());
        assertEquals("u", saved.getValue().getUserId());
    }

    @Test
    void onlyOneProgramRunsAtATime() {
        when(programRepository.findFirstByUserIdAndStatusOrderByStartDateDesc("u", Program.ACTIVE)).thenReturn(Optional.of(program(TODAY)));
        assertThrows(IllegalArgumentException.class,
                () -> service.start(ProgramStartRequest.builder().startDate("2026-10-05").build()));
        verify(programRepository, never()).save(any());
    }

    @Test
    void settingsCanSetPlansButNeverTargets() {
        Program p = owned(TODAY);
        when(programRepository.save(any(Program.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateSettings("p", ProgramSettingsRequest.builder()
                .plans(Map.of("run", "If it's 7am Mon/Wed/Fri, shoes on."))
                .weightKg(80.0)
                .build());

        assertEquals("If it's 7am Mon/Wed/Fri, shoes on.", track(p, "run").getPlan());
        assertEquals(112.0, track(p, "protein").getTarget(), "a new weight doesn't move a target — only a review does");
    }

    @Test
    void movingTheStartKeepsTheLengthAndTheBirthdayOnTheLastDay() {
        Program p = owned(LocalDate.parse("2026-10-05"));
        when(programRepository.save(any(Program.class))).thenAnswer(inv -> inv.getArgument(0));
        service.updateSettings("p", ProgramSettingsRequest.builder().startDate("2026-10-12").build());
        assertEquals(LocalDate.parse("2027-01-09"), p.getEndDate());
        assertEquals(p.getEndDate(), p.getBirthday());
    }

    @Test
    void theReviewIsWhereTargetsChange() {
        Program p = owned(TODAY);
        LocalDate monday = LocalDate.parse("2026-10-12");
        when(reviewRepository.findByUserIdAndProgramIdAndWeekStart("u", "p", monday)).thenReturn(Optional.empty());
        when(reviewRepository.save(any(ProgramReview.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, ProgramReviewRequest.TargetEdit> targets = new LinkedHashMap<>();
        targets.put("run", ProgramReviewRequest.TargetEdit.builder().target(2.0).build());
        targets.put("screen", ProgramReviewRequest.TargetEdit.builder().target(180.0).build());
        ProgramService.ReviewResult result = service.saveReview("p", monday, ProgramReviewRequest.builder()
                .selfTrust(6).win("Three lifts").targets(targets).build());

        assertEquals(2.0, track(p, "run").getTarget());
        assertEquals(180.0, track(p, "screen").getTarget());
        assertEquals(2, result.review().getChanges().size());
        assertEquals(3.0, result.review().getChanges().get(0).getFromTarget());
        assertEquals(6, result.review().getSelfTrust());
        verify(programRepository).save(p);
    }

    @Test
    void savingTheReviewAgainKeepsTheWeeksFirstStartingPoint() {
        Program p = owned(TODAY);
        track(p, "run").setTarget(2.0); // already lowered earlier this week
        LocalDate monday = LocalDate.parse("2026-10-12");
        ProgramReview existing = ProgramReview.builder().id("r").userId("u").programId("p").weekStart(monday)
                .changes(new ArrayList<>(java.util.List.of(ProgramReview.TargetChange.builder()
                        .track("run").fromTarget(3.0).toTarget(2.0).build())))
                .build();
        when(reviewRepository.findByUserIdAndProgramIdAndWeekStart("u", "p", monday)).thenReturn(Optional.of(existing));
        when(reviewRepository.save(any(ProgramReview.class))).thenAnswer(inv -> inv.getArgument(0));

        ProgramService.ReviewResult result = service.saveReview("p", monday, ProgramReviewRequest.builder()
                .selfTrust(7).targets(Map.of("run", ProgramReviewRequest.TargetEdit.builder().target(3.0).build())).build());

        assertTrue(result.review().getChanges().isEmpty(), "back where the week started — nothing changed");
    }

    @Test
    void screenCanGoBackToTheAutomaticCapAndReviewsStartOnMonday() {
        Program p = owned(TODAY);
        track(p, "screen").setTarget(200.0);
        when(reviewRepository.findByUserIdAndProgramIdAndWeekStart(eq("u"), eq("p"), any())).thenReturn(Optional.empty());
        when(reviewRepository.save(any(ProgramReview.class))).thenAnswer(inv -> inv.getArgument(0));
        service.saveReview("p", LocalDate.parse("2026-10-19"), ProgramReviewRequest.builder().selfTrust(5)
                .targets(Map.of("screen", ProgramReviewRequest.TargetEdit.builder().auto(true).build())).build());
        assertNull(track(p, "screen").getTarget());

        assertThrows(IllegalArgumentException.class, () -> service.saveReview("p", LocalDate.parse("2026-10-18"),
                ProgramReviewRequest.builder().selfTrust(5).build()));
    }

    @Test
    void targetsOutsideTheirRangeAreRefused() {
        Program.Track run = Program.Track.builder().key("run").target(3.0).build();
        assertThrows(IllegalArgumentException.class,
                () -> ProgramService.applyTarget(run, ProgramReviewRequest.TargetEdit.builder().target(9.0).build()));
        Program.Track protein = Program.Track.builder().key("protein").target(112.0).floor(84.0).build();
        ProgramService.applyTarget(protein, ProgramReviewRequest.TargetEdit.builder().target(80.0).build());
        assertEquals(80.0, protein.getTarget());
        assertEquals(80.0, protein.getFloor(), "the floor never sits above the target");
    }

    @Test
    void aSealedLetterNeverLeavesTheServer() {
        Program p = owned(TODAY.minusDays(3));
        p.setBirthday(TODAY.plusDays(60));
        when(programRepository.save(any(Program.class))).thenAnswer(inv -> inv.getArgument(0));

        ProgramView view = service.writeLetter("p", "to23", new ProgramLetterRequest("Dear 23 — you did it."));
        assertTrue(view.getLetters().get("to23").isSealed());
        assertNull(view.getLetters().get("to23").getText());
        assertEquals("Dear 23 — you did it.".length(), view.getLetters().get("to23").getLength());
        assertEquals(TODAY.plusDays(60), view.getLetters().get("to23").getOpensOn());

        ProgramView after = service.writeLetter("p", "from23", new ProgramLetterRequest("From 23: keep going."));
        assertFalse(after.getLetters().get("from23").isSealed());
        assertEquals("From 23: keep going.", after.getLetters().get("from23").getText());
    }

    @Test
    void theLetterToTwentyThreeCantBeRewrittenOnceItOpens() {
        Program p = owned(TODAY.minusDays(95));
        p.setBirthday(TODAY.minusDays(1));
        assertThrows(IllegalArgumentException.class, () -> service.writeLetter("p", "to23", new ProgramLetterRequest("late")));
    }

    @Test
    void aMoodCheckInIsAlsoMindsMood() {
        owned(TODAY);
        when(logRepository.save(any(ProgramLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.addLog("p", ProgramLogRequest.builder().track("mood").date(TODAY.toString()).value(2.0).tag("tired").build());

        ArgumentCaptor<MindMoodRequest> mood = ArgumentCaptor.forClass(MindMoodRequest.class);
        verify(mindService).saveMood(eq(TODAY), mood.capture());
        assertEquals(2, mood.getValue().getMoodScore());
        assertEquals("tired", mood.getValue().getMoodNote());
    }

    @Test
    void logsAreCheckedAgainstTheirTrack() {
        owned(TODAY);
        assertThrows(IllegalArgumentException.class, () -> service.addLog("p",
                ProgramLogRequest.builder().track("mood").date(TODAY.toString()).value(7.0).build()));
        assertThrows(IllegalArgumentException.class, () -> service.addLog("p",
                ProgramLogRequest.builder().track("regard").date(TODAY.toString()).level("FULL").build()));
        assertThrows(IllegalArgumentException.class, () -> service.addLog("p",
                ProgramLogRequest.builder().track("run").date(TODAY.plusDays(200).toString()).level("FULL").build()));
        verify(logRepository, never()).save(any());
        verifyNoInteractions(mindService);
    }

    @Test
    void anotherUsersProgramIsNotFound() {
        when(programRepository.findByIdAndUserId("p", "u")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.addLog("p",
                ProgramLogRequest.builder().track("run").date(TODAY.toString()).level("FULL").build()));
    }

    @Test
    void mediaIsDecodedAndCheckedByKind() {
        owned(TODAY);
        when(mediaRepository.countByUserIdAndProgramId("u", "p")).thenReturn(0L);
        when(mediaRepository.save(any(ProgramMedia.class))).thenAnswer(inv -> {
            ProgramMedia m = inv.getArgument(0);
            m.setId("m");
            return m;
        });
        String jpeg = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        var view = service.addMedia("p", ProgramMediaRequest.builder().kind("PHOTO").label("front").date(TODAY.toString()).dataUrl(jpeg).build());
        assertEquals(4, view.getBytes());
        assertNull(view.getDataUrl(), "listings and saves never echo the bytes back");

        String webm = "data:audio/webm;codecs=opus;base64," + Base64.getEncoder().encodeToString(new byte[]{9, 9});
        var audio = service.addMedia("p", ProgramMediaRequest.builder().kind("AUDIO").label("speech").date(TODAY.toString()).dataUrl(webm).durationSec(120).build());
        assertEquals("audio/webm", audio.getMime());

        assertThrows(IllegalArgumentException.class, () -> service.addMedia("p", ProgramMediaRequest.builder()
                .kind("PHOTO").label("front").date(TODAY.toString()).dataUrl("data:text/html;base64,PGI+").build()));
        assertThrows(IllegalArgumentException.class, () -> service.addMedia("p", ProgramMediaRequest.builder()
                .kind("PHOTO").label("speech").date(TODAY.toString()).dataUrl(jpeg).build()));
    }
}
