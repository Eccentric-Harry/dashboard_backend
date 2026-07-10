package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.DailyLogRequest;
import com.personal_dashboard.backend.model.DailyLog;
import com.personal_dashboard.backend.repository.DailyLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyLogService {

    private final DailyLogRepository dailyLogRepository;

    public DailyLog getOrEmptyForDate(LocalDate date) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        return firstLogForDate(date)
                .orElse(DailyLog.builder()
                        .userId(userId)
                        .date(date)
                        .dateString(date.toString())
                        .build());
    }

    public DailyLog upsertForDate(LocalDate date, DailyLogRequest request) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        DailyLog log = firstLogForDate(date)
                .orElse(DailyLog.builder().userId(userId).date(date).dateString(date.toString()).build());

        if (request.getMoodRating() != null) {
            log.setMoodRating(request.getMoodRating());
        }
        if (request.getMoodScore() != null) {
            log.setMoodScore(request.getMoodScore());
        }
        if (request.getMoodNote() != null) {
            log.setMoodNote(request.getMoodNote());
        }

        return dailyLogRepository.save(log);
    }

    public Optional<DailyLog> findByDate(LocalDate date) {
        return firstLogForDate(date);
    }

    public List<DailyLog> getRange(LocalDate startDate, LocalDate endDate) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        return dailyLogRepository.findByUserIdAndDateRange(userId, startDate, endDate).stream()
                .sorted(Comparator.comparing(DailyLog::getDate))
                .toList();
    }

    private Optional<DailyLog> firstLogForDate(LocalDate date) {
        String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
        return dailyLogRepository.findByUserIdAndDateRange(userId, date, date).stream().findFirst();
    }

}
