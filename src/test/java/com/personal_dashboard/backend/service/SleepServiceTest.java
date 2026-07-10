package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.SleepLogRequest;
import com.personal_dashboard.backend.model.SleepLog;
import com.personal_dashboard.backend.repository.SleepLogRepository;
import com.personal_dashboard.backend.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SleepServiceTest {

    @Mock
    private SleepLogRepository sleepLogRepository;

    @InjectMocks
    private SleepService sleepService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("test-user");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void durationCrossesMidnight() {
        assertEquals(450, SleepService.computeDurationMinutes("23:30", "07:00"));
    }

    @Test
    void durationAfterMidnightBedtime() {
        assertEquals(435, SleepService.computeDurationMinutes("01:15", "08:30"));
    }

    @Test
    void durationTreatsEqualTimesAsFullDay() {
        // bed == wake would otherwise be 0; treat it as a wrapped 24h rather than an empty night
        assertEquals(1440, SleepService.computeDurationMinutes("22:00", "22:00"));
    }

    @Test
    void logSleepUpdatesExistingEntryForSameDate() {
        LocalDate date = LocalDate.of(2026, 7, 9);
        SleepLog existing = SleepLog.builder()
                .id("sleep-1")
                .userId("test-user")
                .date(date)
                .bedtime("00:30")
                .wakeTime("06:00")
                .durationMinutes(330)
                .build();
        when(sleepLogRepository.findByUserIdAndDate("test-user", date)).thenReturn(Optional.of(existing));
        when(sleepLogRepository.save(any(SleepLog.class))).thenAnswer(inv -> inv.getArgument(0));

        SleepLog saved = sleepService.logSleep(SleepLogRequest.builder()
                .date("2026-07-09")
                .bedtime("23:00")
                .wakeTime("06:30")
                .quality(4)
                .build());

        assertEquals("sleep-1", saved.getId());
        assertEquals(450, saved.getDurationMinutes());
        assertEquals(4, saved.getQuality());
        assertEquals("manual", saved.getSource());
        verify(sleepLogRepository, times(1)).save(existing);
    }

    @Test
    void logSleepCreatesNewEntryWhenNoneExists() {
        LocalDate date = LocalDate.of(2026, 7, 10);
        when(sleepLogRepository.findByUserIdAndDate("test-user", date)).thenReturn(Optional.empty());
        when(sleepLogRepository.save(any(SleepLog.class))).thenAnswer(inv -> inv.getArgument(0));

        SleepLog saved = sleepService.logSleep(SleepLogRequest.builder()
                .date("2026-07-10")
                .bedtime("22:45")
                .wakeTime("06:15")
                .note("  slept well  ")
                .source("wearable")
                .build());

        assertEquals("test-user", saved.getUserId());
        assertEquals(date, saved.getDate());
        assertEquals(450, saved.getDurationMinutes());
        assertEquals("slept well", saved.getNote());
        assertEquals("wearable", saved.getSource());
    }
}
