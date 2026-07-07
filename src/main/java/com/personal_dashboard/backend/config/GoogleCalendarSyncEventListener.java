package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.CalendarSyncMapping;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.CalendarSyncMappingRepository;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import com.personal_dashboard.backend.service.GoogleCalendarClient;
import com.personal_dashboard.backend.service.GoogleSyncContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeDeleteEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mongo event listener that mirrors DailyTask saves/deletes to every connected
 * Google Calendar account for the task's owner.
 *
 * Insert vs update is determined by whether a CalendarSyncMapping already exists
 * for the (taskId, calendarEmail) pair — NOT by task.getGoogleEventId().
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleCalendarSyncEventListener extends AbstractMongoEventListener<DailyTask> {

    private final GoogleCalendarClient googleCalendarClient;
    private final GoogleSyncStoreRepository syncStoreRepository;
    private final DailyTaskRepository dailyTaskRepository;
    private final CalendarSyncMappingRepository mappingRepository;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @Override
    public void onAfterSave(AfterSaveEvent<DailyTask> event) {
        if (GoogleSyncContext.isBypass()) {
            log.debug("Outbound sync bypassed: save triggered inside sync context.");
            return;
        }

        DailyTask task = event.getSource();
        String userId = task.getUserId();
        if (userId == null || userId.isBlank()) return;

        List<GoogleSyncStore> stores = syncStoreRepository.findByUserId(userId);
        if (stores.isEmpty()) return;

        for (GoogleSyncStore store : stores) {
            String calendarEmail = store.getEmail();
            String storeId = store.getId();
            String mappingId = CalendarSyncMapping.compositeId(task.getId(), calendarEmail);

            executor.submit(() -> {
                try {
                    Optional<CalendarSyncMapping> existingMapping = mappingRepository.findById(mappingId);

                    if (existingMapping.isEmpty()) {
                        // ── Insert ─────────────────────────────────────────────────────────
                        log.info("Outbound: Inserting task '{}' into Google Calendar ({})", task.getTitle(), calendarEmail);
                        String googleEventId = googleCalendarClient.insertEvent(storeId, task);

                        CalendarSyncMapping mapping = CalendarSyncMapping.builder()
                                .id(mappingId)
                                .taskId(task.getId())
                                .userId(userId)
                                .calendarEmail(calendarEmail)
                                .googleEventId(googleEventId)
                                .lastSyncedAt(Instant.now())
                                .build();

                        GoogleSyncContext.setBypass(true);
                        try {
                            mappingRepository.save(mapping);
                            task.setLastSyncedAt(Instant.now());
                            dailyTaskRepository.save(task);
                        } finally {
                            GoogleSyncContext.clear();
                        }
                        log.info("Outbound: Inserted Google Event ID {} ({})", googleEventId, calendarEmail);

                    } else {
                        // ── Update ─────────────────────────────────────────────────────────
                        CalendarSyncMapping mapping = existingMapping.get();
                        log.info("Outbound: Updating Google Event {} for task '{}' ({})",
                                mapping.getGoogleEventId(), task.getTitle(), calendarEmail);

                        String returnedId = googleCalendarClient.updateEvent(storeId, mapping.getGoogleEventId(), task);

                        GoogleSyncContext.setBypass(true);
                        try {
                            // updateEvent returns a new ID if the old event was re-inserted (404 case)
                            if (!returnedId.equals(mapping.getGoogleEventId())) {
                                mapping.setGoogleEventId(returnedId);
                            }
                            mapping.setLastSyncedAt(Instant.now());
                            mappingRepository.save(mapping);
                            task.setLastSyncedAt(Instant.now());
                            dailyTaskRepository.save(task);
                        } finally {
                            GoogleSyncContext.clear();
                        }
                        log.info("Outbound: Updated Google Event successfully ({})", calendarEmail);
                    }

                } catch (Exception e) {
                    log.error("Outbound sync failed for task {} to account {}: {}", task.getId(), calendarEmail, e.getMessage());
                }
            });
        }
    }

    @Override
    public void onBeforeDelete(BeforeDeleteEvent<DailyTask> event) {
        if (GoogleSyncContext.isBypass()) {
            log.debug("Outbound sync bypassed: delete triggered inside sync context.");
            return;
        }

        Document queryDoc = event.getSource();
        if (queryDoc == null || !queryDoc.containsKey("_id")) return;

        String taskId = queryDoc.get("_id").toString();

        // Load all mappings for this task, then delete from each linked Google account
        List<CalendarSyncMapping> mappings = mappingRepository.findByTaskId(taskId);
        for (CalendarSyncMapping mapping : mappings) {
            String storeId = GoogleSyncStore.storeId(mapping.getUserId(), mapping.getCalendarEmail());
            String googleEventId = mapping.getGoogleEventId();

            if (!syncStoreRepository.existsById(storeId)) continue;

            executor.submit(() -> {
                try {
                    log.info("Outbound: Deleting Google Event {} from account {}", googleEventId, mapping.getCalendarEmail());
                    googleCalendarClient.deleteEvent(storeId, googleEventId);
                    mappingRepository.deleteById(mapping.getId());
                    log.info("Outbound: Deleted Google Event successfully");
                } catch (Exception e) {
                    log.error("Failed to delete Google Event {} from {}: {}", googleEventId, mapping.getCalendarEmail(), e.getMessage());
                }
            });
        }
    }
}
