package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.SleepLogRequest;
import com.personal_dashboard.backend.model.SleepLog;
import com.personal_dashboard.backend.repository.SleepLogRepository;
import com.personal_dashboard.backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SleepService {

    private final SleepLogRepository sleepLogRepository;

    public List<SleepLog> getRange(LocalDate startDate, LocalDate endDate) {
        String userId = UserContext.getRequiredUserId();
        return sleepLogRepository.findByUserIdAndDateRange(userId, startDate, endDate)
                .stream()
                .sorted(Comparator.comparing(SleepLog::getDate))
                .toList();
    }

    /**
     * One entry per night: logging the same wake-up date again updates the
     * existing entry instead of failing on the unique user+date index.
     */
    public SleepLog logSleep(SleepLogRequest request) {
        String userId = UserContext.getRequiredUserId();
        LocalDate date = LocalDate.parse(request.getDate());

        SleepLog entry = sleepLogRepository.findByUserIdAndDate(userId, date)
                .orElseGet(() -> SleepLog.builder().userId(userId).date(date).build());
        boolean isUpdate = entry.getId() != null;
        applyRequest(entry, request);
        SleepLog saved = sleepLogRepository.save(entry);
        log.info("{} sleep log for {} — {} to {} ({} min)", isUpdate ? "Updated" : "Created", date,
                request.getBedtime(), request.getWakeTime(), saved.getDurationMinutes());
        return saved;
    }

    public SleepLog updateEntry(String id, SleepLogRequest request) {
        log.info("Updating sleep entry {}", id);
        SleepLog entry = requireEntry(id);
        entry.setDate(LocalDate.parse(request.getDate()));
        applyRequest(entry, request);
        return sleepLogRepository.save(entry);
    }

    public void deleteEntry(String id) {
        log.info("Deleting sleep entry {}", id);
        sleepLogRepository.delete(requireEntry(id));
    }

    private void applyRequest(SleepLog entry, SleepLogRequest request) {
        entry.setBedtime(request.getBedtime());
        entry.setWakeTime(request.getWakeTime());
        entry.setDurationMinutes(computeDurationMinutes(request.getBedtime(), request.getWakeTime()));
        entry.setQuality(request.getQuality());
        entry.setNote(request.getNote() != null && !request.getNote().isBlank() ? request.getNote().trim() : null);
        entry.setSource(request.getSource() != null && !request.getSource().isBlank() ? request.getSource() : "manual");
    }

    /**
     * Bedtime after wake time means the night crossed midnight (23:30 → 07:00);
     * bedtime before it means an after-midnight bedtime (01:15 → 08:30).
     */
    static int computeDurationMinutes(String bedtime, String wakeTime) {
        LocalTime bed = LocalTime.parse(bedtime);
        LocalTime wake = LocalTime.parse(wakeTime);
        long minutes = Duration.between(bed, wake).toMinutes();
        if (minutes <= 0) {
            minutes += Duration.ofDays(1).toMinutes();
        }
        return (int) minutes;
    }

    private SleepLog requireEntry(String id) {
        String userId = UserContext.getRequiredUserId();
        return sleepLogRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Sleep entry not found with id: " + id));
    }
}
