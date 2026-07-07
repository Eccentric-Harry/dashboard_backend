package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.DailyTaskRequest;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyTaskServiceTest {

    @Mock
    private DailyTaskRepository dailyTaskRepository;

    @InjectMocks
    private DailyTaskService dailyTaskService;

    private DailyTask incompleteTask;
    private DailyTask completedTask;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("test-user");
        incompleteTask = DailyTask.builder()
                .id("1")
                .title("Incomplete Task")
                .date(LocalDate.of(2026, 5, 30))
                .completed(false)
                .completedAt(null)
                .build();

        completedTask = DailyTask.builder()
                .id("2")
                .title("Completed Task")
                .date(LocalDate.of(2026, 5, 30))
                .completed(true)
                .completedAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testCreateTask_Incomplete() {
        DailyTaskRequest request = DailyTaskRequest.builder()
                .title("New Task")
                .date("2026-05-30")
                .completed(false)
                .build();

        when(dailyTaskRepository.findByUserIdAndDateRange(eq("test-user"), any(), any())).thenReturn(new ArrayList<>());
        when(dailyTaskRepository.save(any(DailyTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DailyTask result = dailyTaskService.createTask(request);

        assertNotNull(result);
        assertEquals("New Task", result.getTitle());
        assertFalse(result.getCompleted());
        assertNull(result.getCompletedAt());
    }

    @Test
    void testCreateTask_TagsLocalOrigin() {
        DailyTaskRequest request = DailyTaskRequest.builder()
                .title("New Task")
                .date("2026-05-30")
                .completed(false)
                .build();

        when(dailyTaskRepository.findByUserIdAndDateRange(eq("test-user"), any(), any())).thenReturn(new ArrayList<>());
        when(dailyTaskRepository.save(any(DailyTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DailyTask result = dailyTaskService.createTask(request);

        assertNotNull(result.getOrigin());
        assertTrue(result.getOrigin().isLocal());
        assertNull(result.getOrigin().getAccountId());
        // Tombstone flag defaults to not-deleted.
        assertEquals(Boolean.FALSE, result.getDeleted());
    }

    @Test
    void testCreateTask_Completed() {
        DailyTaskRequest request = DailyTaskRequest.builder()
                .title("Completed Task")
                .date("2026-05-30")
                .completed(true)
                .build();

        when(dailyTaskRepository.findByUserIdAndDateRange(eq("test-user"), any(), any())).thenReturn(new ArrayList<>());
        when(dailyTaskRepository.save(any(DailyTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DailyTask result = dailyTaskService.createTask(request);

        assertNotNull(result);
        assertEquals("Completed Task", result.getTitle());
        assertTrue(result.getCompleted());
        assertNotNull(result.getCompletedAt());
        assertTrue(result.getCompletedAt().isAfter(LocalDateTime.now().minusSeconds(5)));
    }

    @Test
    void testUpdateTask_TransitionToCompleted() {
        DailyTaskRequest request = DailyTaskRequest.builder()
                .title("Incomplete Task")
                .date("2026-05-30")
                .completed(true)
                .build();

        when(dailyTaskRepository.findByIdAndUserId("1", "test-user")).thenReturn(Optional.of(incompleteTask));
        when(dailyTaskRepository.save(any(DailyTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DailyTask result = dailyTaskService.updateTask("1", request);

        assertNotNull(result);
        assertTrue(result.getCompleted());
        assertNotNull(result.getCompletedAt());
        assertTrue(result.getCompletedAt().isAfter(LocalDateTime.now().minusSeconds(5)));
    }

    @Test
    void testUpdateTask_TransitionToIncomplete() {
        DailyTaskRequest request = DailyTaskRequest.builder()
                .title("Completed Task")
                .date("2026-05-30")
                .completed(false)
                .build();

        when(dailyTaskRepository.findByIdAndUserId("2", "test-user")).thenReturn(Optional.of(completedTask));
        when(dailyTaskRepository.save(any(DailyTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DailyTask result = dailyTaskService.updateTask("2", request);

        assertNotNull(result);
        assertFalse(result.getCompleted());
        assertNull(result.getCompletedAt());
    }

    @Test
    void testToggleTask() {
        when(dailyTaskRepository.findByIdAndUserId("1", "test-user")).thenReturn(Optional.of(incompleteTask));
        when(dailyTaskRepository.save(any(DailyTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DailyTask result = dailyTaskService.toggleTask("1");

        assertNotNull(result);
        assertTrue(result.getCompleted());
        assertNotNull(result.getCompletedAt());

        // Toggle back
        when(dailyTaskRepository.findByIdAndUserId("1", "test-user")).thenReturn(Optional.of(result));
        DailyTask toggledBack = dailyTaskService.toggleTask("1");

        assertNotNull(toggledBack);
        assertFalse(toggledBack.getCompleted());
        assertNull(toggledBack.getCompletedAt());
    }

    @Test
    void testGetActiveTasks() {
        List<DailyTask> activeTasks = List.of(incompleteTask, completedTask);
        when(dailyTaskRepository.findActiveTasks(eq("test-user"), any(LocalDateTime.class))).thenReturn(activeTasks);

        List<DailyTask> result = dailyTaskService.getActiveTasks();

        assertEquals(2, result.size());
        verify(dailyTaskRepository, times(1)).findActiveTasks(eq("test-user"), any(LocalDateTime.class));
    }

    @Test
    void testSortTasksByDateFirst() {
        DailyTask task1 = DailyTask.builder()
                .id("1")
                .title("Task scheduled for tomorrow")
                .date(LocalDate.of(2026, 5, 31))
                .sortOrder(1)
                .build();

        DailyTask task2 = DailyTask.builder()
                .id("2")
                .title("Task scheduled for today")
                .date(LocalDate.of(2026, 5, 30))
                .sortOrder(2)
                .build();

        List<DailyTask> unsorted = List.of(task1, task2);
        when(dailyTaskRepository.findActiveTasks(eq("test-user"), any(LocalDateTime.class))).thenReturn(unsorted);

        List<DailyTask> sorted = dailyTaskService.getActiveTasks();

        assertEquals("2", sorted.get(0).getId()); // today's task comes first because it has earlier date
        assertEquals("1", sorted.get(1).getId()); // tomorrow's task comes second
    }
}
