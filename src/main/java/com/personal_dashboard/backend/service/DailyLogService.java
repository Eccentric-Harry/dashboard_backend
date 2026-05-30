package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.DailyLogRequest;
import com.personal_dashboard.backend.model.DailyLog;
import com.personal_dashboard.backend.repository.DailyLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyLogService {

    private final DailyLogRepository dailyLogRepository;

    public DailyLog getOrEmptyForDate(LocalDate date) {
        return firstLogForDate(date)
                .orElse(DailyLog.builder()
                        .date(date)
                        .githubCommits(0)
                        .leetCodeSolved(0)
                        .build());
    }

    public DailyLog upsertForDate(LocalDate date, DailyLogRequest request) {
        DailyLog log = firstLogForDate(date)
                .orElse(DailyLog.builder().date(date).build());

        if (request.getMoodRating() != null) {
            log.setMoodRating(request.getMoodRating());
        }
        if (request.getGithubCommits() != null) {
            log.setGithubCommits(request.getGithubCommits());
        }
        if (request.getLeetCodeSolved() != null) {
            log.setLeetCodeSolved(request.getLeetCodeSolved());
        }

        return dailyLogRepository.save(log);
    }

    public Optional<DailyLog> findByDate(LocalDate date) {
        return firstLogForDate(date);
    }

    private Optional<DailyLog> firstLogForDate(LocalDate date) {
        return dailyLogRepository.findByDateRange(date, date).stream().findFirst();
    }
}
