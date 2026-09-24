package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.model.EventOrigin;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.model.GoogleTaskListMapping;
import com.personal_dashboard.backend.model.TaskSyncMapping;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import com.personal_dashboard.backend.repository.GoogleTaskListMappingRepository;
import com.personal_dashboard.backend.repository.TaskSyncMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two-way sync between local planner tasks and Google Tasks.
 *
 * <p>Modelled on {@link GoogleSyncService} but necessarily different in one structural
 * way: Google Tasks has no webhooks and no sync tokens, so inbound changes arrive by
 * polling with an {@code updatedMin} cursor rather than being pushed to us. Everything
 * else — per-account locking, an origin marker for loop prevention, soft-delete instead
 * of resurrection, echo suppression on our own writes — mirrors the calendar side.
 *
 * <h2>Conflict model</h2>
 * A push wins at the moment it happens; a poll wins at the moment it happens. There is
 * no {@code If-Match} on the Tasks API, so a true compare-and-swap is not available. The
 * one guard that matters is implemented here: an inbound change is skipped while the
 * local row has an edit that has not been pushed yet, so a poll landing mid-flight never
 * reverts a change the user just made in the dashboard.
 *
 * <h2>Category ↔ list</h2>
 * Each dashboard category is mirrored as its own Google task list, so a phone widget
 * (which pins exactly one list) can show just "Personal" or just "Learning".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleTasksSyncService {

    private final GoogleTasksClient tasksClient;
    private final GoogleSyncStoreRepository syncStoreRepository;
    private final DailyTaskRepository dailyTaskRepository;
    private final TaskSyncMappingRepository taskMappingRepository;
    private final GoogleTaskListMappingRepository listMappingRepository;
    private final CalendarSyncLocks syncLocks;

    /** How far back completed tasks are still mirrored into Google. */
    @Value("${google.tasks.completed-retention-days:30}")
    private int completedRetentionDays;

    /**
     * Seconds of overlap applied to the {@code updatedMin} cursor. Our clock and Google's
     * are not the same clock, and a task written while a poll is in flight would otherwise
     * fall in the gap and never be seen again. Re-reading a little history each time is
     * cheap; missing a change is permanent.
     */
    @Value("${google.tasks.poll-overlap-seconds:120}")
    private int pollOverlapSeconds;

    @Value("${google.tasks.default-category:General}")
    private String defaultCategory;

    /** Tasks work is serialised per account, separately from calendar work on the same account. */
    private ReentrantLock lockFor(String storeId) {
        return syncLocks.forStore("tasks:" + storeId);
    }

    // ─── Outbound ────────────────────────────────────────────────────────────────

    /**
     * Reconcile one local task against one connected account. Idempotent — the outbox
     * worker may call it more than once, and insert-vs-patch is decided by the mapping.
     */
    public void pushToStore(DailyTask task, GoogleSyncStore store) throws Exception {
        if (!store.isTasksSyncEnabled() || store.isDisconnected()) {
            return;
        }
        String userId = task.getUserId();
        String email = store.getEmail();
        String storeId = store.getId();
        String mappingId = TaskSyncMapping.compositeId(task.getId(), email);

        ReentrantLock lock = lockFor(storeId);
        lock.lock();
        try {
            TaskSyncMapping mapping = taskMappingRepository.findById(mappingId).orElse(null);
            boolean live = mapping != null && !"CANCELLED".equals(mapping.getSyncState());

            // Anything that should no longer be in Google — soft-deleted, retyped to an
            // EVENT, or aged past the retention window — is removed rather than left behind.
            boolean wanted = !Boolean.TRUE.equals(task.getDeleted())
                    && GoogleTasksPushPolicy.shouldPush(task, store, completedRetentionDays);

            if (!wanted) {
                if (live) {
                    log.info("Google Tasks: removing '{}' from {} (deleted/out of scope)", task.getTitle(), email);
                    tasksClient.deleteTask(storeId, mapping.getGoogleTaskListId(), mapping.getGoogleTaskId());
                    mapping.setSyncState("CANCELLED");
                    mapping.setLastSyncedAt(Instant.now());
                    saveBypassing(() -> taskMappingRepository.save(mapping));
                }
                return;
            }

            GoogleTaskListMapping list = resolveTaskList(userId, store, task.getCategory());
            if (list == null) {
                log.warn("Google Tasks: no list resolved for category '{}' on {} — skipping '{}'",
                        task.getCategory(), email, task.getTitle());
                return;
            }

            JsonNode remote;
            if (!live) {
                remote = tasksClient.insertTask(storeId, list.getGoogleTaskListId(), task);
                log.info("Google Tasks: inserted '{}' into list '{}' ({})", task.getTitle(), list.getCategory(), email);
            } else if (!list.getGoogleTaskListId().equals(mapping.getGoogleTaskListId())) {
                // The category changed. Google Tasks can only move a task within a list,
                // so crossing lists means delete-then-insert; the id necessarily changes.
                log.info("Google Tasks: moving '{}' to list '{}' ({})", task.getTitle(), list.getCategory(), email);
                tasksClient.deleteTask(storeId, mapping.getGoogleTaskListId(), mapping.getGoogleTaskId());
                remote = tasksClient.insertTask(storeId, list.getGoogleTaskListId(), task);
            } else {
                remote = tasksClient.patchTask(storeId, list.getGoogleTaskListId(), mapping.getGoogleTaskId(), task);
                if (remote == null) {
                    log.info("Google Tasks: '{}' vanished remotely — re-inserting ({})", task.getTitle(), email);
                    remote = tasksClient.insertTask(storeId, list.getGoogleTaskListId(), task);
                }
            }

            TaskSyncMapping toSave = mapping != null ? mapping : TaskSyncMapping.builder()
                    .id(mappingId)
                    .taskId(task.getId())
                    .userId(userId)
                    .accountEmail(email)
                    .build();
            toSave.setGoogleTaskId(remote.get("id").asText());
            toSave.setGoogleTaskListId(list.getGoogleTaskListId());
            toSave.setEtag(GoogleTasksClient.text(remote, "etag"));
            toSave.setRemoteUpdatedAt(parseInstant(GoogleTasksClient.text(remote, "updated")));
            toSave.setSyncState("SYNCED");
            toSave.setLastSyncedAt(Instant.now());
            saveBypassing(() -> taskMappingRepository.save(toSave));

        } finally {
            lock.unlock();
        }
    }

    /**
     * Mirror every in-scope task into a freshly enabled account. Safe to re-run: tasks
     * that already have a live mapping are skipped by {@link #pushToStore}.
     */
    public int pushAll(String userId, GoogleSyncStore store) {
        int pushed = 0;
        List<DailyTask> candidates = dailyTaskRepository.findByUserId(userId).stream()
                .filter(t -> !Boolean.TRUE.equals(t.getDeleted()))
                .filter(t -> GoogleTasksPushPolicy.shouldPush(t, store, completedRetentionDays))
                .toList();
        log.info("Google Tasks: initial push of {} task(s) to {}", candidates.size(), store.getEmail());
        for (DailyTask task : candidates) {
            try {
                pushToStore(task, store);
                pushed++;
            } catch (Exception e) {
                log.error("Google Tasks: failed to push '{}' to {}: {}", task.getTitle(), store.getEmail(), e.getMessage());
            }
        }
        return pushed;
    }

    // ─── Inbound ─────────────────────────────────────────────────────────────────

    /** Poll one account for changes made in Google Tasks (phone, web, widget). */
    public void pollAccount(String userId, String email) throws Exception {
        String storeId = GoogleSyncStore.storeId(userId, email);
        GoogleSyncStore store = syncStoreRepository.findById(storeId).orElse(null);
        if (store == null || store.isDisconnected() || !store.isTasksSyncEnabled()) {
            return;
        }

        ReentrantLock lock = lockFor(storeId);
        lock.lock();
        try {
            // Taken before any request so that anything written during this poll is
            // re-read next time rather than falling into the gap.
            Instant pollStartedAt = Instant.now();
            Instant updatedMin = store.getTasksLastPolledAt() == null
                    ? null
                    : store.getTasksLastPolledAt().minusSeconds(pollOverlapSeconds);

            bindRemoteLists(userId, store);

            List<GoogleTaskListMapping> lists = listMappingRepository.findByUserIdAndAccountEmail(userId, email);
            int applied = 0;
            for (GoogleTaskListMapping list : lists) {
                if (Boolean.TRUE.equals(list.getMissing())) {
                    continue;
                }
                applied += pollList(userId, store, list, updatedMin);
            }

            store.setTasksLastPolledAt(pollStartedAt);
            store.setTasksLastSyncedAt(Instant.now());
            store.setTasksScopeGranted(true);
            syncStoreRepository.save(store);
            log.info("Google Tasks poll complete for {} — {} change(s) applied across {} list(s)",
                    email, applied, lists.size());

        } catch (GoogleApiExecutor.MissingScopeException e) {
            log.warn("Google Tasks: account {} has not granted the Tasks scope — disabling until reconnect", email);
            store.setTasksScopeGranted(false);
            syncStoreRepository.save(store);
        } finally {
            lock.unlock();
        }
    }

    private int pollList(String userId, GoogleSyncStore store, GoogleTaskListMapping list, Instant updatedMin)
            throws Exception {
        String storeId = store.getId();
        String pageToken = null;
        int applied = 0;
        do {
            GoogleTasksClient.TaskPage page =
                    tasksClient.listTasks(storeId, list.getGoogleTaskListId(), updatedMin, pageToken);

            if (page.isListMissing()) {
                // Deleted in Google. The binding is kept and flagged so the category's
                // tasks are not silently re-scattered into whatever list comes next.
                list.setMissing(true);
                listMappingRepository.save(list);
                log.warn("Google Tasks: list '{}' was deleted in Google ({})", list.getCategory(), store.getEmail());
                return applied;
            }

            for (JsonNode node : page.getItems()) {
                try {
                    if (applyRemoteTask(userId, store, list, node)) {
                        applied++;
                    }
                } catch (Exception e) {
                    log.error("Google Tasks: failed to apply remote task {}: {}",
                            GoogleTasksClient.text(node, "id"), e.getMessage());
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);

        list.setLastSyncedAt(Instant.now());
        listMappingRepository.save(list);
        return applied;
    }

    /** Apply one remote task to the local store. Returns true when something changed. */
    private boolean applyRemoteTask(String userId, GoogleSyncStore store, GoogleTaskListMapping list, JsonNode node) {
        String googleTaskId = GoogleTasksClient.text(node, "id");
        if (googleTaskId == null) {
            return false;
        }
        Instant remoteUpdated = parseInstant(GoogleTasksClient.text(node, "updated"));
        boolean remoteDeleted = node.path("deleted").asBoolean(false);

        TaskSyncMapping mapping = taskMappingRepository.findByGoogleTaskIdAndUserId(googleTaskId, userId).orElse(null);

        if (mapping == null) {
            if (remoteDeleted) {
                return false; // a deletion of something we never had
            }
            return createLocalTask(userId, store, list, node, googleTaskId, remoteUpdated);
        }

        DailyTask task = dailyTaskRepository.findById(mapping.getTaskId()).orElse(null);
        if (task == null) {
            log.warn("Google Tasks: mapping {} points at a missing local task — dropping it", mapping.getId());
            taskMappingRepository.delete(mapping);
            return false;
        }

        // Echo suppression: a remote copy no newer than the one we last wrote or read is
        // our own write coming back. Compared only against Google's clock, never ours.
        if (mapping.getRemoteUpdatedAt() != null && remoteUpdated != null
                && !remoteUpdated.isAfter(mapping.getRemoteUpdatedAt())) {
            return false;
        }

        // Don't let a poll revert an edit that hasn't reached Google yet. The pending
        // push is authoritative and will overwrite the remote copy moments from now.
        if (hasUnpushedLocalEdit(task, mapping)) {
            log.info("Google Tasks: skipping inbound change for '{}' — a local edit is still pending push", task.getTitle());
            return false;
        }

        if (remoteDeleted) {
            if (Boolean.TRUE.equals(task.getDeleted())) {
                return false;
            }
            log.info("Google Tasks: '{}' was deleted in Google — soft-deleting locally", task.getTitle());
            task.setDeleted(true);
            task.setDeletedAt(Instant.now());
            task.setLastSyncedAt(Instant.now());
            saveBypassing(() -> dailyTaskRepository.save(task));
            mapping.setSyncState("CANCELLED");
        } else {
            applyRemoteFields(node, task);
            task.setLastSyncedAt(Instant.now());
            saveBypassing(() -> dailyTaskRepository.save(task));
        }

        mapping.setRemoteUpdatedAt(remoteUpdated);
        mapping.setEtag(GoogleTasksClient.text(node, "etag"));
        mapping.setLastSyncedAt(Instant.now());
        saveBypassing(() -> taskMappingRepository.save(mapping));
        return true;
    }

    private boolean createLocalTask(String userId, GoogleSyncStore store, GoogleTaskListMapping list,
                                    JsonNode node, String googleTaskId, Instant remoteUpdated) {
        DailyTask task = new DailyTask();
        task.setUserId(userId);
        // Google *Tasks* is the one inbound source allowed to mint a planner task.
        task.setItemType("TASK");
        task.setCategory(list.getCategory());
        task.setColor("#c9bff6");
        task.setAllDay(true);
        task.setRecurrenceFrequency("NONE");
        task.setOrigin(EventOrigin.googleTasks(store.getEmail(), list.getGoogleTaskListId()));
        applyRemoteFields(node, task);
        task.setLastSyncedAt(Instant.now());

        saveBypassing(() -> dailyTaskRepository.save(task));

        TaskSyncMapping mapping = TaskSyncMapping.builder()
                .id(TaskSyncMapping.compositeId(task.getId(), store.getEmail()))
                .taskId(task.getId())
                .userId(userId)
                .accountEmail(store.getEmail())
                .googleTaskId(googleTaskId)
                .googleTaskListId(list.getGoogleTaskListId())
                .etag(GoogleTasksClient.text(node, "etag"))
                .remoteUpdatedAt(remoteUpdated)
                .syncState("SYNCED")
                .lastSyncedAt(Instant.now())
                .build();
        saveBypassing(() -> taskMappingRepository.save(mapping));

        log.info("Google Tasks: imported '{}' from list '{}' ({})", task.getTitle(), list.getCategory(), store.getEmail());
        return true;
    }

    /**
     * Copy the fields Google actually owns onto the local task.
     *
     * <p>Deliberately does <em>not</em> touch {@code scheduledTime}/{@code startTime}:
     * the Tasks API discards the time of day on write, so Google has no opinion about it
     * and reading one back would erase a time the user set in the dashboard.
     */
    private void applyRemoteFields(JsonNode node, DailyTask task) {
        String title = GoogleTasksClient.text(node, "title");
        task.setTitle(title != null ? title : "Untitled");
        task.setNotes(GoogleTasksClient.text(node, "notes"));

        String due = GoogleTasksClient.text(node, "due");
        if (due != null) {
            LocalDate dueDate = parseDueDate(due);
            if (dueDate != null) {
                task.setDate(dueDate);
            }
        } else if (task.getDate() == null) {
            // Google allows an undated task; the dashboard's schema does not.
            task.setDate(LocalDate.now());
        }

        boolean completed = "completed".equalsIgnoreCase(GoogleTasksClient.text(node, "status"));
        boolean wasCompleted = Boolean.TRUE.equals(task.getCompleted());
        task.setCompleted(completed);
        task.setStatus(completed ? "DONE" : "TODO");
        if (completed && !wasCompleted) {
            LocalDateTime at = Optional.ofNullable(parseInstant(GoogleTasksClient.text(node, "completed")))
                    .map(i -> LocalDateTime.ofInstant(i, ZoneId.systemDefault()))
                    .orElse(LocalDateTime.now());
            task.setCompletedAt(at);
        } else if (!completed) {
            task.setCompletedAt(null);
        }
    }

    /**
     * True when the local row carries an edit newer than the last time this mapping was
     * reconciled — i.e. an outbox push is still in flight for it.
     */
    private boolean hasUnpushedLocalEdit(DailyTask task, TaskSyncMapping mapping) {
        Instant localUpdated = task.getUpdatedAt();
        Instant syncedAt = mapping.getLastSyncedAt();
        return localUpdated != null && syncedAt != null && localUpdated.isAfter(syncedAt);
    }

    // ─── Task lists ──────────────────────────────────────────────────────────────

    /**
     * Find or create the Google task list mirroring a dashboard category.
     * An existing Google list whose title already matches is adopted rather than
     * duplicated — connecting the dashboard should not leave the user with two
     * "Personal" lists.
     */
    GoogleTaskListMapping resolveTaskList(String userId, GoogleSyncStore store, String category) throws Exception {
        String effective = (category == null || category.isBlank()) ? defaultCategory : category.trim();
        String id = GoogleTaskListMapping.compositeId(userId, store.getEmail(), effective);

        GoogleTaskListMapping existing = listMappingRepository.findById(id).orElse(null);
        if (existing != null && !Boolean.TRUE.equals(existing.getMissing())) {
            return existing;
        }

        String title = listTitleFor(effective);
        String remoteId = null;
        for (JsonNode remote : tasksClient.listTaskLists(store.getId())) {
            if (title.equalsIgnoreCase(GoogleTasksClient.text(remote, "title"))) {
                remoteId = GoogleTasksClient.text(remote, "id");
                break;
            }
        }
        if (remoteId == null) {
            remoteId = tasksClient.insertTaskList(store.getId(), title);
            log.info("Google Tasks: created list '{}' on {}", title, store.getEmail());
        }

        GoogleTaskListMapping mapping = existing != null ? existing : GoogleTaskListMapping.builder()
                .id(id)
                .userId(userId)
                .accountEmail(store.getEmail())
                .category(effective)
                .createdAt(Instant.now())
                .build();
        mapping.setCategory(effective);
        mapping.setGoogleTaskListId(remoteId);
        mapping.setGoogleTaskListTitle(title);
        mapping.setMissing(false);
        mapping.setLastSyncedAt(Instant.now());
        listMappingRepository.save(mapping);
        return mapping;
    }

    /**
     * Bind any Google list we don't know about yet, so tasks created on the phone in a
     * list the dashboard never made still come in (under a category named after the list).
     */
    private void bindRemoteLists(String userId, GoogleSyncStore store) throws Exception {
        List<GoogleTaskListMapping> known = listMappingRepository.findByUserIdAndAccountEmail(userId, store.getEmail());
        List<String> knownIds = new ArrayList<>(known.stream().map(GoogleTaskListMapping::getGoogleTaskListId).toList());

        for (JsonNode remote : tasksClient.listTaskLists(store.getId())) {
            String remoteId = GoogleTasksClient.text(remote, "id");
            String title = GoogleTasksClient.text(remote, "title");
            if (remoteId == null || knownIds.contains(remoteId)) {
                continue;
            }
            String category = categoryForListTitle(title);
            String id = GoogleTaskListMapping.compositeId(userId, store.getEmail(), category);
            if (listMappingRepository.existsById(id)) {
                continue; // same category already bound to a different list — leave it alone
            }
            listMappingRepository.save(GoogleTaskListMapping.builder()
                    .id(id)
                    .userId(userId)
                    .accountEmail(store.getEmail())
                    .category(category)
                    .googleTaskListId(remoteId)
                    .googleTaskListTitle(title)
                    .missing(false)
                    .createdAt(Instant.now())
                    .lastSyncedAt(Instant.now())
                    .build());
            knownIds.add(remoteId);
            log.info("Google Tasks: adopted existing list '{}' as category '{}' ({})", title, category, store.getEmail());
        }
    }

    /** The Google list title for a category. Kept identical so adoption-by-title works. */
    static String listTitleFor(String category) {
        return category;
    }

    /** Google's default list is called "My Tasks"; map it onto the dashboard's catch-all. */
    private String categoryForListTitle(String title) {
        if (title == null || title.isBlank()) {
            return defaultCategory;
        }
        String trimmed = title.trim();
        return trimmed.toLowerCase(Locale.ROOT).equals("my tasks") ? defaultCategory : trimmed;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Google returns {@code due} as a full RFC 3339 timestamp at midnight UTC even though
     * only the date is meaningful, so the date is read straight off the UTC instant. Doing
     * this in the local zone would shift every due date back a day for anyone east of UTC.
     */
    static LocalDate parseDueDate(String due) {
        try {
            return ZonedDateTime.parse(due).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
        } catch (Exception e) {
            try {
                return LocalDate.parse(due.substring(0, 10));
            } catch (Exception ignored) {
                log.warn("Google Tasks: unparseable due date '{}'", due);
                return null;
            }
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            try {
                return ZonedDateTime.parse(value).toInstant();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** Run a save under the sync-context bypass so it doesn't enqueue an outbound echo. */
    private void saveBypassing(Runnable work) {
        GoogleSyncContext.setBypass(true);
        try {
            work.run();
        } finally {
            GoogleSyncContext.clear();
        }
    }
}
