package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal_dashboard.backend.model.CalendarSyncMapping;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.EventOrigin;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.CalendarSyncMappingRepository;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
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
    private final CalendarSyncMappingRepository mappingRepository;
    private final CalendarSyncLocks syncLocks;
    private final ExecutorService syncExecutor = Executors.newFixedThreadPool(4);

    // ─── Outbound: push local-only tasks to a specific Google account ───────────

    /**
     * Push all tasks that have no CalendarSyncMapping for the given calendar email.
     * Safe to call repeatedly — already-synced tasks are skipped.
     * Returns the number of tasks successfully pushed.
     */
    public int pushLocalEventsToGoogle(String userId, String calendarEmail) {
        String storeId = GoogleSyncStore.storeId(userId, calendarEmail);
        log.info("Outbound push starting: userId={} calendarEmail={}", userId, calendarEmail);

        GoogleSyncStore store = syncStoreRepository.findById(storeId).orElse(null);
        if (store == null) {
            log.warn("Outbound push aborting — no store for {}", storeId);
            return 0;
        }

        List<DailyTask> allTasks = dailyTaskRepository.findByUserId(userId);
        log.info("Found {} total task(s) for user", allTasks.size());

        int pushed = 0;
        for (DailyTask task : allTasks) {
            // Recurring tasks are pushed as native Google recurring events (RRULE).
            // Origin gate (guardrail #4 + cross-account #10).
            if (!SyncPushPolicy.shouldPush(task, store)) {
                continue;
            }
            // Skip anything already soft-deleted — nothing to push.
            if (Boolean.TRUE.equals(task.getDeleted())) {
                continue;
            }
            String mappingId = CalendarSyncMapping.compositeId(task.getId(), calendarEmail);
            if (mappingRepository.existsById(mappingId)) {
                continue; // already synced to this account
            }
            try {
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

                log.info("Pushed task '{}' → Google Event ID: {} (account: {})", task.getTitle(), googleEventId, calendarEmail);
                pushed++;
            } catch (Exception e) {
                log.error("Failed to push task '{}' ({}) to {}: {}", task.getTitle(), task.getId(), calendarEmail, e.getMessage());
            }
        }
        log.info("Outbound push complete: {}/{} tasks pushed to {}", pushed, allTasks.size(), calendarEmail);
        return pushed;
    }

    // ─── Inbound: pull from Google Calendar ─────────────────────────────────────

    /**
     * Schedule an asynchronous sync for a specific Google account.
     */
    public void triggerSyncAsync(String userId, String calendarEmail, boolean forceFullSync) {
        syncExecutor.submit(() -> {
            try {
                syncCalendar(userId, calendarEmail, forceFullSync);
            } catch (Exception e) {
                log.error("Async sync failed for userId={} calendarEmail={}", userId, calendarEmail, e);
            }
        });
    }

    /**
     * Backward-compat overload: look up all accounts for the userId and sync them all.
     * Used by the webhook controller which only knows userId (via the store).
     */
    public void triggerSyncAsync(String userId, boolean forceFullSync) {
        List<GoogleSyncStore> stores = syncStoreRepository.findByUserId(userId);
        for (GoogleSyncStore store : stores) {
            triggerSyncAsync(userId, store.getEmail(), forceFullSync);
        }
    }

    /**
     * Synchronise events from one Google Calendar account (incremental or full).
     */
    public void syncCalendar(String userId, String calendarEmail, boolean forceFullSync) throws Exception {
        String storeId = GoogleSyncStore.storeId(userId, calendarEmail);

        // Per-(account,calendar) lock: serialise all sync work on this calendar so
        // inbound and outbound cannot interleave. Reentrant for the 410 full-resync.
        java.util.concurrent.locks.ReentrantLock lock = syncLocks.forStore(storeId);
        lock.lock();
        try {
            syncCalendarLocked(userId, calendarEmail, forceFullSync, storeId);
        } finally {
            lock.unlock();
        }
    }

    private void syncCalendarLocked(String userId, String calendarEmail, boolean forceFullSync, String storeId) throws Exception {
        log.info("Starting Google Calendar sync: storeId={} forceFullSync={}", storeId, forceFullSync);

        GoogleSyncStore store = syncStoreRepository.findById(storeId).orElse(null);
        if (store == null) {
            log.warn("Sync aborting — no credentials found for storeId: {}", storeId);
            return;
        }
        if (store.isDisconnected()) {
            log.warn("Sync aborting — account {} is DISCONNECTED ({}). Reconnect required.", storeId, store.getAuthError());
            return;
        }

        try {
            GoogleSyncContext.setBypass(true);

            String syncToken = forceFullSync ? null : store.getCurrentSyncToken();
            List<JsonNode> allGoogleEvents = new ArrayList<>();
            String pageToken = null;
            String nextSyncToken = null;
            boolean syncTokenExpired = false;

            do {
                GoogleCalendarClient.SyncEventsResponse response =
                        googleCalendarClient.listEvents(storeId, syncToken, pageToken);

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
                log.warn("Sync token invalid (410 Gone) for storeId: {}. Rebuilding via full sync.", storeId);
                store.setCurrentSyncToken(null);
                syncStoreRepository.save(store);
                syncCalendar(userId, calendarEmail, true);
                return;
            }

            processGoogleEvents(userId, calendarEmail, allGoogleEvents);

            if (nextSyncToken != null) {
                store.setCurrentSyncToken(nextSyncToken);
            }
            store.setLastSyncedAt(Instant.now());
            syncStoreRepository.save(store);
            log.info("Google Calendar sync complete for storeId: {}", storeId);

        } finally {
            GoogleSyncContext.clear();
        }
    }

    // ─── Internal ────────────────────────────────────────────────────────────────

    private void processGoogleEvents(String userId, String calendarEmail, List<JsonNode> googleEvents) {
        log.info("Processing {} changed event(s) from Google Calendar (account: {})", googleEvents.size(), calendarEmail);

        for (JsonNode eventNode : googleEvents) {
            try {
                String googleEventId = eventNode.get("id").asText();
                String status = eventNode.has("status") ? eventNode.get("status").asText() : "confirmed";

                // ── Recurrence: flag & defer ───────────────────────────────────────────
                // A recurring master carries a "recurrence" (RRULE/EXDATE) array; a modified
                // or cancelled instance carries "recurringEventId". Neither is supported yet,
                // so skip cleanly rather than importing a broken single event or tombstoning
                // based on an instance. Deliberately not half-built.
                if (eventNode.has("recurrence") || eventNode.has("recurringEventId")) {
                    log.info("Inbound: Skipping recurring event {} (recurrence not yet supported — flagged & deferred).",
                            googleEventId);
                    continue;
                }

                // Look up mapping by googleEventId + userId (account-agnostic — handles cross-account moves)
                Optional<CalendarSyncMapping> mappingOpt = mappingRepository.findByGoogleEventIdAndUserId(googleEventId, userId);

                if ("cancelled".equalsIgnoreCase(status)) {
                    mappingOpt.ifPresent(mapping -> {
                        // Soft-delete the local task (never hard-delete) and KEEP the mapping so a
                        // later full resync still resolves this googleEventId to the soft-delete
                        // instead of re-creating the event. Runs under GoogleSyncContext bypass,
                        // so the soft-deleted save is not pushed back to Google.
                        DailyTask task = dailyTaskRepository.findById(mapping.getTaskId()).orElse(null);
                        if (task != null && !Boolean.TRUE.equals(task.getDeleted())) {
                            log.info("Inbound: Tombstoning local task {} for cancelled Google event {}",
                                    task.getId(), googleEventId);
                            task.setDeleted(true);
                            task.setDeletedAt(Instant.now());
                            task.setLastSyncedAt(Instant.now());
                            dailyTaskRepository.save(task);
                        }
                        mapping.setSyncState("CANCELLED");
                        mapping.setLastSyncedAt(Instant.now());
                        mappingRepository.save(mapping);
                    });
                    continue;
                }

                String updatedStr = eventNode.get("updated").asText();
                Instant googleUpdated = Instant.parse(updatedStr);
                String iCalUID = readText(eventNode, "iCalUID");

                // ── Dedup precedence: googleEventId → iCalUID → create ──────────────────
                // Matching on googleEventId alone duplicates shared invites, so fall back
                // to the RFC 5545 iCalUID (which is stable across calendars/accounts)
                // before ever creating a new local task. Both lookups include soft-deleted records.
                CalendarSyncMapping mapping = mappingOpt.orElse(null);
                DailyTask existingTask = null;
                if (mapping != null) {
                    existingTask = dailyTaskRepository.findById(mapping.getTaskId()).orElse(null);
                } else if (iCalUID != null) {
                    existingTask = dailyTaskRepository.findByUserAndICalUID(userId, iCalUID)
                            .stream().findFirst().orElse(null);
                }

                // ── Resurrection guard ─────────────────────────────────────────────────
                // A match that is soft-deleted is NEVER revived or duplicated, even on a full
                // 410 resync. Ensure a CANCELLED mapping links this googleEventId so the
                // next pull short-circuits on it, then skip.
                if (existingTask != null && Boolean.TRUE.equals(existingTask.getDeleted())) {
                    log.info("Inbound: Event {} resolves to soft-deleted task {} (matched by {}). Leaving deleted — no resurrection.",
                            googleEventId, existingTask.getId(), mapping != null ? "googleEventId" : "iCalUID");
                    if (mapping == null) {
                        mappingRepository.save(newMapping(existingTask.getId(), userId, calendarEmail,
                                googleEventId, readText(eventNode, "etag"), "CANCELLED"));
                    }
                    continue;
                }

                if (mapping != null && existingTask != null) {
                    // ── Update existing copy (matched by googleEventId) ─────────────────
                    // Skip our own echo (a write we just pushed coming back).
                    if (mapping.getLastSyncedAt() != null) {
                        long diffMs = Math.abs(googleUpdated.toEpochMilli() - mapping.getLastSyncedAt().toEpochMilli());
                        if (diffMs < 5000) {
                            log.info("Inbound: Skipping duplicate echo for event {} (diff {}ms)", googleEventId, diffMs);
                            continue;
                        }
                    }
                    // LWW groundwork (enforcement lands in the conflict-resolution commit):
                    // log both clocks on every inbound apply so google-vs-local skew is
                    // debuggable. These are DIFFERENT clocks — do not compare for equality.
                    log.info("Inbound: Updating local task {} for event {} [googleUpdated={} localUpdatedAt={}]",
                            existingTask.getId(), googleEventId, googleUpdated, existingTask.getUpdatedAt());
                    mapGoogleEventToLocal(eventNode, existingTask);
                    existingTask.setLastSyncedAt(Instant.now());
                    dailyTaskRepository.save(existingTask);
                    mapping.setEtag(readText(eventNode, "etag"));
                    mapping.setLastSyncedAt(Instant.now());
                    mappingRepository.save(mapping);

                } else if (existingTask != null) {
                    // ── Link only (matched by iCalUID, no mapping for this account) ─────
                    // A shared invite already known locally under another account. Link this
                    // account's copy to the existing task instead of creating a duplicate;
                    // do NOT overwrite task content here (cross-account merge belongs to the
                    // conflict-resolution commit).
                    log.info("Inbound: Linking event {} to existing task {} by iCalUID (shared invite — no duplicate).",
                            googleEventId, existingTask.getId());
                    mappingRepository.save(newMapping(existingTask.getId(), userId, calendarEmail,
                            googleEventId, readText(eventNode, "etag"), "SYNCED"));

                } else {
                    // ── Create a new local task + mapping ───────────────────────────────
                    log.info("Inbound: Creating new local task for event: {}", googleEventId);
                    DailyTask newTask = new DailyTask();
                    newTask.setUserId(userId);
                    newTask.setItemType("TASK");
                    newTask.setCategory("Personal");
                    newTask.setColor("#c9bff6");
                    // Immutable source-of-truth marker: this event was born in Google.
                    newTask.setOrigin(EventOrigin.google(calendarEmail, "primary"));
                    mapGoogleEventToLocal(eventNode, newTask);
                    newTask.setLastSyncedAt(Instant.now());
                    dailyTaskRepository.save(newTask);

                    mappingRepository.save(newMapping(newTask.getId(), userId, calendarEmail,
                            googleEventId, readText(eventNode, "etag"), "SYNCED"));
                }

            } catch (Exception e) {
                log.error("Failed to process Google event: {}", eventNode, e);
            }
        }
    }

    private void mapGoogleEventToLocal(JsonNode eventNode, DailyTask task) {
        String summary = eventNode.has("summary") ? eventNode.get("summary").asText() : "Google Calendar Event";
        String description = eventNode.has("description") ? eventNode.get("description").asText() : "";
        task.setTitle(summary);
        task.setNotes(description);

        // RFC 5545 UID — stable across calendars/accounts. Persisted now for the
        // second-tier dedup key; matching still runs on googleEventId in commit 1.
        String iCalUID = readText(eventNode, "iCalUID");
        if (iCalUID != null) {
            task.setICalUID(iCalUID);
        }

        if (eventNode.has("colorId")) {
            String colorId = eventNode.get("colorId").asText();
            String hexColor = getGoogleColorHex(colorId);
            if (hexColor != null) {
                task.setColor(hexColor);
                task.setCategory(getCategoryFromGoogleColor(colorId));
            }
        } else if (task.getColor() == null) {
            task.setColor("#6d28d9");
            task.setCategory("Personal");
        }

        JsonNode startNode = eventNode.get("start");
        JsonNode endNode = eventNode.get("end");

        if (startNode.has("date")) {
            task.setDate(LocalDate.parse(startNode.get("date").asText()));
            task.setAllDay(true);
            task.setStartTime(null);
            task.setEndTime(null);
            task.setScheduledTime(null);
            task.setTimeZone(null); // all-day events carry no timezone
        } else if (startNode.has("dateTime")) {
            ZonedDateTime zdt = ZonedDateTime.parse(startNode.get("dateTime").asText());
            task.setDate(zdt.toLocalDate());
            task.setAllDay(false);
            // Preserve the original IANA zone id (not a raw offset) so wall-clock
            // time survives DST. Prefer Google's start.timeZone; fall back to the
            // zone parsed from the dateTime offset.
            String tz = readText(startNode, "timeZone");
            task.setTimeZone(tz != null ? tz : zdt.getZone().getId());
            String startTime = zdt.format(DateTimeFormatter.ofPattern("HH:mm"));
            task.setStartTime(startTime);
            task.setScheduledTime(startTime);
            if (endNode != null && endNode.has("dateTime")) {
                task.setEndTime(ZonedDateTime.parse(endNode.get("dateTime").asText())
                        .format(DateTimeFormatter.ofPattern("HH:mm")));
            }
        }
    }

    /** Builds a CalendarSyncMapping linking a local task to a remote copy. */
    private CalendarSyncMapping newMapping(String taskId, String userId, String calendarEmail,
                                           String googleEventId, String etag, String syncState) {
        return CalendarSyncMapping.builder()
                .id(CalendarSyncMapping.compositeId(taskId, calendarEmail))
                .taskId(taskId)
                .userId(userId)
                .calendarEmail(calendarEmail)
                .googleEventId(googleEventId)
                .etag(etag)
                .syncState(syncState)
                .lastSyncedAt(Instant.now())
                .build();
    }

    /** Null-safe text extraction: returns null for missing, null, or blank nodes. */
    private String readText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText();
        return (value == null || value.isBlank()) ? null : value;
    }

    // Returns the modern (darker) Google event color hex so that outbound round-trips
    // are lossless: inbound colorId→hex stored on task, outbound hex→nearest colorId
    // via GoogleCalendarClient.hexToGoogleColorId picks the same colorId back.
    private String getGoogleColorHex(String colorId) {
        if (colorId == null) return null;
        return switch (colorId) {
            case "1"  -> "#7986cb"; // Lavender
            case "2"  -> "#33b679"; // Sage
            case "3"  -> "#8e24aa"; // Grape
            case "4"  -> "#e67c73"; // Flamingo
            case "5"  -> "#f6bf26"; // Banana
            case "6"  -> "#f4511e"; // Tangerine
            case "7"  -> "#039be5"; // Peacock
            case "8"  -> "#616161"; // Graphite
            case "9"  -> "#3f51b5"; // Blueberry
            case "10" -> "#0b8043"; // Basil
            case "11" -> "#d50000"; // Tomato
            default   -> null;
        };
    }

    private String getCategoryFromGoogleColor(String colorId) {
        if (colorId == null) return "Personal";
        return switch (colorId) {
            case "1", "4"       -> "Social";
            case "2", "7", "10" -> "Health";
            case "3"            -> "Learning";
            case "5", "6"       -> "Finance";
            case "8", "9", "11" -> "Work";
            default             -> "Personal";
        };
    }
}
