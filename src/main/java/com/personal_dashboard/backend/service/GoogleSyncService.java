package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleSyncService {

    private final GoogleCalendarClient googleCalendarClient;
    private final GoogleSyncStoreRepository syncStoreRepository;
    private final DailyTaskRepository dailyTaskRepository;
    private final ExecutorService syncExecutor = Executors.newFixedThreadPool(4);

    /**
     * Submit an asynchronous sync request for a user
     */
    public void triggerSyncAsync(String userId, boolean forceFullSync) {
        syncExecutor.submit(() -> {
            try {
                syncCalendar(userId, forceFullSync);
            } catch (Exception e) {
                log.error("Asynchronous sync execution failed for user: {}", userId, e);
            }
        });
    }

    /**
     * Synchronize calendar events (supports incremental and full synchronization)
     */
    public synchronized void syncCalendar(String userId, boolean forceFullSync) throws Exception {
        log.info("Starting Google Calendar sync for user: {} (forceFullSync={})", userId, forceFullSync);
        
        GoogleSyncStore store = syncStoreRepository.findById(userId).orElse(null);
        if (store == null) {
            log.warn("Sync aborting. Google sync store credentials not found for user: {}", userId);
            return;
        }

        try {
            // Establish ThreadLocal bypass context to prevent database writes from triggering outbound loops
            GoogleSyncContext.setBypass(true);

            String syncToken = forceFullSync ? null : store.getCurrentSyncToken();
            List<JsonNode> allGoogleEvents = new ArrayList<>();
            String pageToken = null;
            String nextSyncToken = null;
            boolean syncTokenExpired = false;

            do {
                GoogleCalendarClient.SyncEventsResponse response = googleCalendarClient.listEvents(userId, syncToken, pageToken);
                
                if (response.isGone()) {
                    syncTokenExpired = true;
                    break;
                }

                allGoogleEvents.addAll(response.getItems());
                pageToken = response.getNextPageToken();
                if (response.getNextSyncToken() != null) {
                    nextSyncToken = response.getNextSyncToken();
                }
            } while (pageToken != null);

            if (syncTokenExpired) {
                log.warn("Sync token is invalid/expired (410 Gone) for user: {}. Rebuilding state via full sync.", userId);
                // Clear the expired token and execute full history rebuild
                store.setCurrentSyncToken(null);
                syncStoreRepository.save(store);
                syncCalendar(userId, true);
                return;
            }

            // Process Google changes
            processGoogleEvents(userId, allGoogleEvents);

            // Save new sync token for future incremental syncs
            if (nextSyncToken != null) {
                store.setCurrentSyncToken(nextSyncToken);
            }
            store.setLastSyncedAt(Instant.now());
            syncStoreRepository.save(store);
            log.info("Successfully completed Google Calendar sync for user: {}", userId);

        } finally {
            GoogleSyncContext.clear();
        }
    }

    /**
     * Process list of changed events from Google Calendar
     */
    private void processGoogleEvents(String userId, List<JsonNode> googleEvents) {
        log.info("Processing {} changed event(s) from Google Calendar", googleEvents.size());

        for (JsonNode eventNode : googleEvents) {
            try {
                String googleEventId = eventNode.get("id").asText();
                String status = eventNode.has("status") ? eventNode.get("status").asText() : "confirmed";

                Optional<DailyTask> localTaskOpt = findLocalTaskByGoogleId(googleEventId, userId);

                // Handle Deleted / Cancelled events
                if ("cancelled".equalsIgnoreCase(status)) {
                    if (localTaskOpt.isPresent()) {
                        log.info("Inbound sync: Deleting local task associated with cancelled Google Event: {}", googleEventId);
                        dailyTaskRepository.delete(localTaskOpt.get());
                    }
                    continue;
                }

                // Parse Google updated timestamp (RFC3339 format)
                String updatedStr = eventNode.get("updated").asText();
                Instant googleUpdated = Instant.parse(updatedStr);

                // Dedup Loop Defense: Check if local modification occurred within a tight buffer window
                if (localTaskOpt.isPresent()) {
                    DailyTask localTask = localTaskOpt.get();
                    Instant lastSynced = localTask.getLastSyncedAt();
                    
                    if (lastSynced != null) {
                        long diffMs = Math.abs(googleUpdated.toEpochMilli() - lastSynced.toEpochMilli());
                        if (diffMs < 5000) {
                            // If the change occurred within 5 seconds, it is highly likely our own outbound write echoed back.
                            log.info("Inbound sync: Discarding duplicate update for event {} (Time difference: {} ms)", googleEventId, diffMs);
                            continue;
                        }
                    }
                    
                    // Update existing task
                    log.info("Inbound sync: Updating local task for event: {}", googleEventId);
                    mapGoogleEventToLocal(eventNode, localTask);
                    localTask.setLastSyncedAt(Instant.now());
                    dailyTaskRepository.save(localTask);
                } else {
                    // Create new task
                    log.info("Inbound sync: Creating new local task for event: {}", googleEventId);
                    DailyTask newTask = new DailyTask();
                    newTask.setUserId(userId);
                    newTask.setGoogleEventId(googleEventId);
                    newTask.setItemType("TASK");
                    newTask.setCategory("Personal");
                    newTask.setColor("#c9bff6");
                    
                    mapGoogleEventToLocal(eventNode, newTask);
                    newTask.setLastSyncedAt(Instant.now());
                    dailyTaskRepository.save(newTask);
                }

            } catch (Exception e) {
                log.error("Failed to process Google event item: {}", eventNode, e);
            }
        }
    }

    private Optional<DailyTask> findLocalTaskByGoogleId(String googleEventId, String userId) {
        return dailyTaskRepository.findByGoogleEventIdAndUserId(googleEventId, userId);
    }

    /**
     * Map Google Event Resource values to local DailyTask entity
     */
    private void mapGoogleEventToLocal(JsonNode eventNode, DailyTask task) {
        String summary = eventNode.has("summary") ? eventNode.get("summary").asText() : "Google Calendar Event";
        String description = eventNode.has("description") ? eventNode.get("description").asText() : "";

        task.setTitle(summary);
        task.setNotes(description);

        if (eventNode.has("colorId")) {
            String colorId = eventNode.get("colorId").asText();
            String hexColor = getGoogleColorHex(colorId);
            if (hexColor != null) {
                task.setColor(hexColor);
                task.setCategory(getCategoryFromGoogleColor(colorId));
            }
        } else {
            if (task.getColor() == null) {
                task.setColor("#6d28d9"); // Default personal brand color
                task.setCategory("Personal");
            }
        }

        JsonNode startNode = eventNode.get("start");
        JsonNode endNode = eventNode.get("end");

        if (startNode.has("date")) {
            // All-day event
            LocalDate startDate = LocalDate.parse(startNode.get("date").asText());
            task.setDate(startDate);
            task.setAllDay(true);
            task.setStartTime(null);
            task.setEndTime(null);
            task.setScheduledTime(null);
        } else if (startNode.has("dateTime")) {
            // Timed event
            String startDateTimeStr = startNode.get("dateTime").asText();
            ZonedDateTime zdt = ZonedDateTime.parse(startDateTimeStr);
            
            task.setDate(zdt.toLocalDate());
            task.setAllDay(false);

            // Format start time as HH:mm
            String startTime = zdt.format(DateTimeFormatter.ofPattern("HH:mm"));
            task.setStartTime(startTime);
            task.setScheduledTime(startTime);

            if (endNode != null && endNode.has("dateTime")) {
                ZonedDateTime endZdt = ZonedDateTime.parse(endNode.get("dateTime").asText());
                String endTime = endZdt.format(DateTimeFormatter.ofPattern("HH:mm"));
                task.setEndTime(endTime);
            }
        }
    }

    private String getGoogleColorHex(String colorId) {
        if (colorId == null) return null;
        return switch (colorId) {
            case "1" -> "#a4bdfc"; // Lavender
            case "2" -> "#7ae7bf"; // Sage
            case "3" -> "#dbadff"; // Grape
            case "4" -> "#ff887c"; // Flamingo
            case "5" -> "#fbd75b"; // Banana / Yellow
            case "6" -> "#ffb878"; // Tangerine
            case "7" -> "#46d6db"; // Peacock
            case "8" -> "#e1e1e1"; // Graphite
            case "9" -> "#5484ed"; // Blueberry
            case "10" -> "#51b749"; // Basil
            case "11" -> "#dc2127"; // Tomato
            default -> null;
        };
    }

    private String getCategoryFromGoogleColor(String colorId) {
        if (colorId == null) return "Personal";
        return switch (colorId) {
            case "1", "4" -> "Social";
            case "2", "7", "10" -> "Health";
            case "3" -> "Learning";
            case "5", "6" -> "Finance";
            case "8", "9", "11" -> "Work";
            default -> "Personal";
        };
    }
}
