package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.WorryLedgerResponse;
import com.personal_dashboard.backend.dto.request.MindLaneRequest;
import com.personal_dashboard.backend.dto.request.MindNoticedRequest;
import com.personal_dashboard.backend.dto.request.MindVerdictRequest;
import com.personal_dashboard.backend.model.MindEntry;
import com.personal_dashboard.backend.model.WorryPrediction;
import com.personal_dashboard.backend.repository.MindEntryRepository;
import com.personal_dashboard.backend.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Covers the two pieces of MindService a user is actually asked to trust: the ledger
 * arithmetic (a number offered as evidence against their own catastrophising, so it has
 * to be right) and sealed-text redaction (a privacy guarantee, so it has to hold).
 */
@ExtendWith(MockitoExtension.class)
class MindServiceTest {

    private static final String USER = "test-user";

    @Mock private MindEntryRepository mindEntryRepository;
    @Mock private DailyTaskService dailyTaskService;
    @Mock private DailyLogService dailyLogService;
    @Mock private com.personal_dashboard.backend.repository.DailyTaskRepository dailyTaskRepository;
    @Mock private com.personal_dashboard.backend.repository.FocusSessionRepository focusSessionRepository;
    @Mock private com.personal_dashboard.backend.repository.LearningRepository learningRepository;
    @Mock private com.personal_dashboard.backend.repository.StravaActivityRepository stravaActivityRepository;
    @Mock private com.personal_dashboard.backend.repository.SleepLogRepository sleepLogRepository;

    @InjectMocks private MindService mindService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private MindEntry worry(Integer predicted, String outcome, String severity) {
        return MindEntry.builder()
                .id("w-" + predicted + "-" + outcome)
                .userId(USER)
                .type("THOUGHT")
                .lane("WORRY")
                .status("PARKED")
                .date(LocalDate.of(2026, 9, 1))
                .prediction(WorryPrediction.builder()
                        .fearedOutcome("something bad")
                        .predictedProbability(predicted)
                        .outcome(outcome)
                        .severity(severity)
                        .build())
                .build();
    }

    // ---------- Worry ledger ----------

    @Test
    void ledgerIsEmptyWhenNothingPredicted() {
        when(mindEntryRepository.findByUserId(USER)).thenReturn(List.of());

        WorryLedgerResponse ledger = mindService.getWorryLedger();

        assertEquals(0, ledger.getTotalPredicted());
        assertEquals(0, ledger.getTotalResolved());
        // Null, not 0.0 — an invented "0% of your worries came true" would be a number the
        // user has no reason to believe, and this instrument only works if they believe it.
        assertNull(ledger.getMeanPredictedProbability());
        assertNull(ledger.getActualOccurrenceRate());
    }

    @Test
    void ledgerCountsOutcomesAndAveragesPredictedProbability() {
        when(mindEntryRepository.findByUserId(USER)).thenReturn(List.of(
                worry(80, "NOT_HAPPENED", null),
                worry(60, "NOT_HAPPENED", null),
                worry(40, "HAPPENED", "BETTER")
        ));

        WorryLedgerResponse ledger = mindService.getWorryLedger();

        assertEquals(3, ledger.getTotalPredicted());
        assertEquals(3, ledger.getTotalResolved());
        assertEquals(2, ledger.getNotHappened());
        assertEquals(1, ledger.getHappened());
        assertEquals(60.0, ledger.getMeanPredictedProbability(), 0.001);
        assertEquals(100.0 / 3, ledger.getActualOccurrenceRate(), 0.001);
        assertEquals(1, ledger.getCopedBetter());
    }

    @Test
    void ledgerCountsPartlyAsHalfAnOccurrence() {
        // Two worries, one of which partly landed: 0.5 / 2 = 25%. Calling a partial hit
        // "nothing" would flatter the user, and a ledger that flatters is worthless.
        when(mindEntryRepository.findByUserId(USER)).thenReturn(List.of(
                worry(70, "NOT_HAPPENED", null),
                worry(70, "PARTLY", "BETTER")
        ));

        WorryLedgerResponse ledger = mindService.getWorryLedger();

        assertEquals(1, ledger.getPartly());
        assertEquals(25.0, ledger.getActualOccurrenceRate(), 0.001);
    }

    @Test
    void ledgerIgnoresUnresolvedPredictionsInTheRateButCountsThemAsLogged() {
        when(mindEntryRepository.findByUserId(USER)).thenReturn(List.of(
                worry(90, "NOT_HAPPENED", null),
                worry(50, null, null)
        ));

        WorryLedgerResponse ledger = mindService.getWorryLedger();

        assertEquals(2, ledger.getTotalPredicted());
        assertEquals(1, ledger.getTotalResolved());
        assertEquals(0.0, ledger.getActualOccurrenceRate(), 0.001);
        // Still averaged over both — the prediction was made whether or not it's answered yet.
        assertEquals(70.0, ledger.getMeanPredictedProbability(), 0.001);
    }

    @Test
    void verdictOfNotHappenedClearsSeverityAndResolvesTheWorry() {
        MindEntry entry = worry(75, null, null);
        entry.setStatus("VERDICT_DUE");
        entry.setReviewDate(LocalDate.of(2026, 9, 5));
        when(mindEntryRepository.findByIdAndUserId(anyString(), eq(USER))).thenReturn(Optional.of(entry));
        when(mindEntryRepository.save(any(MindEntry.class))).thenAnswer(i -> i.getArgument(0));

        MindEntry saved = mindService.saveVerdict("w1",
                MindVerdictRequest.builder().outcome("NOT_HAPPENED").severity("WORSE").build());

        assertEquals("NOT_HAPPENED", saved.getPrediction().getOutcome());
        assertNull(saved.getPrediction().getSeverity(), "severity is meaningless when nothing happened");
        assertNotNull(saved.getPrediction().getRecordedAt());
        assertEquals("RESOLVED", saved.getStatus());
        assertNull(saved.getReviewDate(), "an answered worry leaves the parking lot");
    }

    @Test
    void verdictRejectsUnknownOutcome() {
        MindEntry entry = worry(75, null, null);
        when(mindEntryRepository.findByIdAndUserId(anyString(), eq(USER))).thenReturn(Optional.of(entry));

        assertThrows(IllegalArgumentException.class, () -> mindService.saveVerdict("w1",
                MindVerdictRequest.builder().outcome("MAYBE").build()));
    }

    // ---------- Sealed text ----------

    @Test
    void noticedSealsSuppliedTextAndReturnsItRedacted() {
        when(mindEntryRepository.save(any(MindEntry.class))).thenAnswer(i -> i.getArgument(0));

        MindEntry saved = mindService.noticed(MindNoticedRequest.builder()
                .text("something I never want to read again")
                .category("IMMORAL")
                .intensity(4)
                .build());

        assertTrue(saved.getTextSealed());
        assertNull(saved.getText(), "sealed text must not come back out on the write path either");
        assertEquals("NOTICED", saved.getStatus());
        assertEquals("INTRUSIVE", saved.getLane());
        assertEquals("IMMORAL", saved.getIntrusive().getCategory());
        assertNotNull(saved.getResolvedAt(), "a noticed thought closes immediately");
    }

    @Test
    void noticedWithNoTextIsTheOneTapPath() {
        when(mindEntryRepository.save(any(MindEntry.class))).thenAnswer(i -> i.getArgument(0));

        MindEntry saved = mindService.noticed(new MindNoticedRequest());

        assertNull(saved.getText());
        assertNotEquals(Boolean.TRUE, saved.getTextSealed(), "nothing to seal when nothing was typed");
        assertEquals("UNNAMED", saved.getIntrusive().getCategory());
    }

    @Test
    void noticedFallsBackToUnnamedForAnUnknownCategory() {
        when(mindEntryRepository.save(any(MindEntry.class))).thenAnswer(i -> i.getArgument(0));

        MindEntry saved = mindService.noticed(MindNoticedRequest.builder().category("SOMETHING_ELSE").build());

        assertEquals("UNNAMED", saved.getIntrusive().getCategory());
    }

    @Test
    void getEntriesStripsSealedTextButLeavesTheStoredEntryIntact() {
        MindEntry sealed = MindEntry.builder()
                .id("s1").userId(USER).type("THOUGHT").lane("INTRUSIVE").status("NOTICED")
                .text("sealed content").textSealed(true).date(LocalDate.of(2026, 9, 1))
                .build();
        MindEntry plain = MindEntry.builder()
                .id("p1").userId(USER).type("THOUGHT").status("OPEN")
                .text("ordinary thought").date(LocalDate.of(2026, 9, 1))
                .build();
        when(mindEntryRepository.findDueParked(eq(USER), any(LocalDate.class))).thenReturn(List.of());
        when(mindEntryRepository.findByUserId(USER)).thenReturn(List.of(sealed, plain));

        List<MindEntry> entries = mindService.getEntries(null, null);

        MindEntry returnedSealed = entries.stream().filter(e -> "s1".equals(e.getId())).findFirst().orElseThrow();
        MindEntry returnedPlain = entries.stream().filter(e -> "p1".equals(e.getId())).findFirst().orElseThrow();
        assertNull(returnedSealed.getText());
        assertEquals("ordinary thought", returnedPlain.getText());
        // Redaction returns a copy; the stored document keeps its text so the sealed
        // archive can still show it, and so a later save() can never wipe it.
        assertEquals("sealed content", sealed.getText());
    }

    @Test
    void triagingIntoTheIntrusiveLaneSealsExistingText() {
        MindEntry entry = MindEntry.builder()
                .id("e1").userId(USER).type("THOUGHT").status("OPEN")
                .text("typed before I knew what it was").date(LocalDate.of(2026, 9, 1))
                .build();
        when(mindEntryRepository.findByIdAndUserId(anyString(), eq(USER))).thenReturn(Optional.of(entry));
        when(mindEntryRepository.save(any(MindEntry.class))).thenAnswer(i -> i.getArgument(0));

        MindEntry saved = mindService.setLane("e1", MindLaneRequest.builder().lane("intrusive").build());

        assertEquals("INTRUSIVE", saved.getLane());
        assertTrue(entry.getTextSealed());
        assertNull(saved.getText(), "the response is redacted the moment it becomes intrusive");
    }

    @Test
    void triagingRejectsAnUnknownLane() {
        MindEntry entry = MindEntry.builder().id("e1").userId(USER).status("OPEN").build();
        when(mindEntryRepository.findByIdAndUserId(anyString(), eq(USER))).thenReturn(Optional.of(entry));

        assertThrows(IllegalArgumentException.class,
                () -> mindService.setLane("e1", MindLaneRequest.builder().lane("SOMETHING").build()));
    }

    // ---------- Resurfacing ----------

    @Test
    void parkedWorryWithAnUnansweredPredictionResurfacesForItsVerdict() {
        MindEntry predicted = worry(80, null, null);
        predicted.setReviewDate(LocalDate.of(2026, 9, 1));
        MindEntry plain = MindEntry.builder()
                .id("plain").userId(USER).type("THOUGHT").status("PARKED")
                .reviewDate(LocalDate.of(2026, 9, 1)).date(LocalDate.of(2026, 8, 25))
                .build();
        when(mindEntryRepository.findDueParked(eq(USER), any(LocalDate.class)))
                .thenReturn(List.of(predicted, plain));
        when(mindEntryRepository.findByUserId(USER)).thenReturn(List.of());

        mindService.getEntries(null, null);

        assertEquals("VERDICT_DUE", predicted.getStatus());
        assertNotNull(predicted.getReviewDate(), "kept so the prompt can say when it was parked");
        // Unchanged behaviour for worries carrying no prediction.
        assertEquals("OPEN", plain.getStatus());
        assertNull(plain.getReviewDate());
        verify(mindEntryRepository).saveAll(any());
    }
}
