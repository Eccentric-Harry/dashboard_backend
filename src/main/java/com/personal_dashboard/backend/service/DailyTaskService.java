package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.DailyTaskRequest;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyTaskService {

    private final DailyTaskRepository dailyTaskRepository;

    public List<DailyTask> getTasksForDate(LocalDate date) {
        return sortTasks(dailyTaskRepository.findByDateRange(date, date.plusDays(1)));
    }

    public List<DailyTask> getTasksForDateWithIncompletePrevious(LocalDate date) {
        return sortTasks(dailyTaskRepository.findTasksForDateWithIncompletePrevious(date, date.plusDays(1)));
    }

    public List<DailyTask> getTasksForRange(LocalDate startDate, LocalDate endDate) {
        return sortTasks(dailyTaskRepository.findByDateRange(startDate, endDate.plusDays(1)));
    }

    public DailyTask createTask(DailyTaskRequest request) {
        LocalDate taskDate = LocalDate.parse(request.getDate());
        int nextOrder = dailyTaskRepository.findByDateRange(taskDate, taskDate.plusDays(1)).size();
        DailyTask task = DailyTask.builder()
                .title(request.getTitle())
                .date(LocalDate.parse(request.getDate()))
                .scheduledTime(request.getScheduledTime())
                .notes(request.getNotes())
                .completed(request.getCompleted() != null ? request.getCompleted() : false)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : nextOrder)
                .build();
        return dailyTaskRepository.save(task);
    }

    public DailyTask updateTask(String id, DailyTaskRequest request) {
        DailyTask existing = dailyTaskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with id: " + id));

        existing.setTitle(request.getTitle());
        existing.setDate(LocalDate.parse(request.getDate()));
        existing.setScheduledTime(request.getScheduledTime());
        existing.setNotes(request.getNotes());
        if (request.getCompleted() != null) {
            existing.setCompleted(request.getCompleted());
        }
        if (request.getSortOrder() != null) {
            existing.setSortOrder(request.getSortOrder());
        }

        return dailyTaskRepository.save(existing);
    }

    public DailyTask toggleTask(String id) {
        DailyTask existing = dailyTaskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with id: " + id));
        existing.setCompleted(existing.getCompleted() == null || !existing.getCompleted());
        return dailyTaskRepository.save(existing);
    }

    public void deleteTask(String id) {
        if (!dailyTaskRepository.existsById(id)) {
            throw new IllegalArgumentException("Task not found with id: " + id);
        }
        dailyTaskRepository.deleteById(id);
    }

    private List<DailyTask> sortTasks(List<DailyTask> tasks) {
        return tasks.stream()
                .sorted(Comparator
                        .comparing(DailyTask::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DailyTask::getScheduledTime, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(DailyTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
