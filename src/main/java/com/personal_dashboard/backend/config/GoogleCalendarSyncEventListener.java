package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.DailyTask;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleCalendarSyncEventListener extends AbstractMongoEventListener<DailyTask> {

    private final GoogleCalendarClient googleCalendarClient;
    private final GoogleSyncStoreRepository syncStoreRepository;
    private final DailyTaskRepository dailyTaskRepository;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @Override
    public void onAfterSave(AfterSaveEvent<DailyTask> event) {
        if (GoogleSyncContext.isBypass()) {
            log.debug("Outbound sync bypassed: Entity save triggered inside synchronization context.");
            return;
        }

        DailyTask task = event.getSource();
        String userId = task.getUserId();
        if (userId == null || userId.isBlank()) {
            return;
        }

        // Check if user is connected to Google Calendar sync
        if (!syncStoreRepository.existsById(userId)) {
            return;
        }

        executor.submit(() -> {
            try {
                if (task.getGoogleEventId() == null || task.getGoogleEventId().isBlank()) {
                    // Create flow
                    log.info("Outbound sync: Inserting new event to Google Calendar for task: {}", task.getId());
                    String googleEventId = googleCalendarClient.insertEvent(userId, task);
                    
                    // Save Google ID to local document using sync bypass
                    try {
                        GoogleSyncContext.setBypass(true);
                        task.setGoogleEventId(googleEventId);
                        task.setLastSyncedAt(Instant.now());
                        dailyTaskRepository.save(task);
                    } finally {
                        GoogleSyncContext.clear();
                    }
                    log.info("Outbound sync: Inserted event with Google ID: {}", googleEventId);
                } else {
                    // Update flow
                    log.info("Outbound sync: Updating event {} in Google Calendar for task: {}", task.getGoogleEventId(), task.getId());
                    googleCalendarClient.updateEvent(userId, task);
                    
                    // Update lastSyncedAt using sync bypass
                    try {
                        GoogleSyncContext.setBypass(true);
                        task.setLastSyncedAt(Instant.now());
                        dailyTaskRepository.save(task);
                    } finally {
                        GoogleSyncContext.clear();
                    }
                    log.info("Outbound sync: Updated event successfully");
                }
            } catch (Exception e) {
                log.error("Failed to perform outbound sync to Google Calendar for task {}", task.getId(), e);
            }
        });
    }

    @Override
    public void onBeforeDelete(BeforeDeleteEvent<DailyTask> event) {
        if (GoogleSyncContext.isBypass()) {
            log.debug("Outbound sync bypassed: Entity deletion triggered inside synchronization context.");
            return;
        }

        Document queryDoc = event.getSource();
        if (queryDoc == null || !queryDoc.containsKey("_id")) {
            return;
        }

        String id = queryDoc.get("_id").toString();
        // Retrieve task to check for Google Event ID and User ID
        dailyTaskRepository.findById(id).ifPresent(task -> {
            String userId = task.getUserId();
            String googleEventId = task.getGoogleEventId();
            
            if (userId != null && !userId.isBlank() && googleEventId != null && !googleEventId.isBlank()) {
                if (syncStoreRepository.existsById(userId)) {
                    executor.submit(() -> {
                        try {
                            log.info("Outbound sync: Deleting event {} from Google Calendar", googleEventId);
                            googleCalendarClient.deleteEvent(userId, googleEventId);
                            log.info("Outbound sync: Deleted event successfully");
                        } catch (Exception e) {
                            log.error("Failed to delete Google Calendar event {} on deletion", googleEventId, e);
                        }
                    });
                }
            }
        });
    }
}
