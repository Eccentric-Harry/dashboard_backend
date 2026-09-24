package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.model.GoogleTaskListMapping;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import com.personal_dashboard.backend.repository.GoogleTaskListMappingRepository;
import com.personal_dashboard.backend.repository.TaskSyncMappingRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.service.GoogleTasksClient;
import com.personal_dashboard.backend.service.GoogleTasksSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manage Google <em>Tasks</em> mirroring. Deliberately separate from
 * {@link GoogleOAuthController}: the OAuth connection is shared, but whether planner
 * tasks are mirrored into Google Tasks is its own opt-in, because enabling it creates
 * task lists in the user's Google account.
 */
@RestController
@RequestMapping("/api/v1/google-tasks")
@RequiredArgsConstructor
@Tag(name = "Google Tasks Sync", description = "Mirror planner tasks into Google Tasks for phone widgets")
@Slf4j
public class GoogleTasksController {

    private final GoogleSyncStoreRepository syncStoreRepository;
    private final GoogleTaskListMappingRepository listMappingRepository;
    private final TaskSyncMappingRepository taskMappingRepository;
    private final GoogleTasksSyncService tasksSyncService;
    private final GoogleTasksClient tasksClient;

    private final ExecutorService worker = Executors.newFixedThreadPool(2);

    // ─── Status ──────────────────────────────────────────────────────────────────

    @GetMapping("/status")
    @Operation(summary = "Google Tasks sync status",
               description = "Per-account mirroring state, granted scope, and the lists in use")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        String userId = UserContext.getRequiredUserId();
        List<GoogleSyncStore> stores = syncStoreRepository.findByUserId(userId);

        List<Map<String, Object>> accounts = new ArrayList<>();
        for (GoogleSyncStore store : stores) {
            Map<String, Object> a = new HashMap<>();
            a.put("email", store.getEmail());
            a.put("enabled", store.isTasksSyncEnabled());
            a.put("scopeGranted", Boolean.TRUE.equals(store.getTasksScopeGranted()));
            a.put("status", store.getStatus() != null ? store.getStatus() : "CONNECTED");
            a.put("lastSyncedAt", store.getTasksLastSyncedAt() != null ? store.getTasksLastSyncedAt().toString() : "");
            a.put("syncedTaskCount", taskMappingRepository.countByUserIdAndAccountEmail(userId, store.getEmail()));
            a.put("lists", listMappingRepository.findByUserIdAndAccountEmail(userId, store.getEmail()).stream()
                    .map(l -> Map.of(
                            "category", l.getCategory(),
                            "title", l.getGoogleTaskListTitle() == null ? l.getCategory() : l.getGoogleTaskListTitle(),
                            "missing", Boolean.TRUE.equals(l.getMissing())))
                    .toList());
            accounts.add(a);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("connected", !stores.isEmpty());
        data.put("anyEnabled", stores.stream().anyMatch(GoogleSyncStore::isTasksSyncEnabled));
        data.put("accounts", accounts);
        return ok("google-tasks-status", data);
    }

    // ─── Enable / disable ────────────────────────────────────────────────────────

    @PostMapping("/enable")
    @Operation(summary = "Enable Google Tasks mirroring",
               description = "Creates one Google task list per category and pushes in-scope tasks. "
                           + "Pass ?email=... for one account, omit for all connected accounts.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enable(
            @RequestParam(value = "email", required = false) String email) {

        String userId = UserContext.getRequiredUserId();
        List<GoogleSyncStore> targets = targets(userId, email);
        if (targets.isEmpty()) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(error("google-tasks-enable", "No connected Google account" + (email == null ? "" : ": " + email)));
        }

        Map<String, Object> byAccount = new HashMap<>();
        for (GoogleSyncStore store : targets) {
            // A grant made before Tasks sync existed is valid for Calendar but 403s on
            // Tasks. Probe once here so the user gets "reconnect to approve" now, rather
            // than an enabled-looking toggle that never syncs anything.
            if (!tasksClient.hasTasksScope(store.getId())) {
                store.setTasksScopeGranted(false);
                syncStoreRepository.save(store);
                byAccount.put(store.getEmail(), "reconnect_required");
                continue;
            }
            store.setTasksScopeGranted(true);
            store.setTasksSyncEnabled(true);
            // Null cursor = the first poll reads everything already in Google Tasks.
            store.setTasksLastPolledAt(null);
            syncStoreRepository.save(store);
            byAccount.put(store.getEmail(), "enabled");

            // The initial mirror can be hundreds of calls; run it off the request thread.
            worker.submit(() -> {
                try {
                    tasksSyncService.pollAccount(userId, store.getEmail());
                    int pushed = tasksSyncService.pushAll(userId, store);
                    log.info("Google Tasks: initial sync for {} pushed {} task(s)", store.getEmail(), pushed);
                } catch (Exception e) {
                    log.error("Google Tasks: initial sync failed for {}", store.getEmail(), e);
                }
            });
        }

        Map<String, Object> data = new HashMap<>();
        data.put("status", "ok");
        data.put("byAccount", byAccount);
        return ok("google-tasks-enable", data);
    }

    @PostMapping("/disable")
    @Operation(summary = "Disable Google Tasks mirroring",
               description = "Stops mirroring. Lists and tasks already in Google are left untouched; "
                           + "pass ?purge=true to also drop the local sync mappings.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disable(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "purge", defaultValue = "false") boolean purge) {

        String userId = UserContext.getRequiredUserId();
        List<GoogleSyncStore> targets = targets(userId, email);

        for (GoogleSyncStore store : targets) {
            store.setTasksSyncEnabled(false);
            syncStoreRepository.save(store);
            if (purge) {
                // Mappings only — never the user's Google data. Re-enabling then adopts
                // the existing lists by title instead of duplicating them.
                taskMappingRepository.deleteByUserIdAndAccountEmail(userId, store.getEmail());
                listMappingRepository.deleteByUserIdAndAccountEmail(userId, store.getEmail());
            }
        }

        return ok("google-tasks-disable", Map.of(
                "status", "disabled",
                "accounts", targets.stream().map(GoogleSyncStore::getEmail).toList(),
                "purged", purge));
    }

    // ─── Manual sync ─────────────────────────────────────────────────────────────

    @PostMapping("/sync")
    @Operation(summary = "Sync now", description = "Runs a poll and a full push immediately, off the request thread")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncNow(
            @RequestParam(value = "email", required = false) String email) {

        String userId = UserContext.getRequiredUserId();
        List<GoogleSyncStore> targets = targets(userId, email).stream()
                .filter(GoogleSyncStore::isTasksSyncEnabled)
                .toList();

        if (targets.isEmpty()) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(error("google-tasks-sync", "Google Tasks sync is not enabled for any connected account"));
        }

        for (GoogleSyncStore store : targets) {
            worker.submit(() -> {
                try {
                    tasksSyncService.pollAccount(userId, store.getEmail());
                    tasksSyncService.pushAll(userId, store);
                } catch (Exception e) {
                    log.error("Google Tasks: manual sync failed for {}", store.getEmail(), e);
                }
            });
        }

        return ok("google-tasks-sync", Map.of(
                "status", "sync_scheduled",
                "accounts", targets.stream().map(GoogleSyncStore::getEmail).toList()));
    }

    // ─── Diagnostics ─────────────────────────────────────────────────────────────

    @GetMapping("/diagnostics")
    @Operation(summary = "Sync diagnostics", description = "What is bound where, and when it last ran")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnostics() {
        String userId = UserContext.getRequiredUserId();
        Map<String, Object> data = new HashMap<>();
        data.put("generatedAt", Instant.now().toString());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (GoogleSyncStore store : syncStoreRepository.findByUserId(userId)) {
            Map<String, Object> row = new HashMap<>();
            row.put("email", store.getEmail());
            row.put("enabled", store.isTasksSyncEnabled());
            row.put("scopeGranted", Boolean.TRUE.equals(store.getTasksScopeGranted()));
            row.put("lastPolledAt", store.getTasksLastPolledAt() != null ? store.getTasksLastPolledAt().toString() : "");
            row.put("mappedTasks", taskMappingRepository.countByUserIdAndAccountEmail(userId, store.getEmail()));
            List<GoogleTaskListMapping> lists = listMappingRepository.findByUserIdAndAccountEmail(userId, store.getEmail());
            row.put("lists", lists.stream().map(l -> Map.of(
                    "category", l.getCategory(),
                    "googleTaskListId", l.getGoogleTaskListId(),
                    "missing", Boolean.TRUE.equals(l.getMissing()),
                    "lastSyncedAt", l.getLastSyncedAt() != null ? l.getLastSyncedAt().toString() : "")).toList());
            rows.add(row);
        }
        data.put("accounts", rows);
        return ok("google-tasks-diagnostics", data);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private List<GoogleSyncStore> targets(String userId, String email) {
        List<GoogleSyncStore> stores = syncStoreRepository.findByUserId(userId);
        if (email == null || email.isBlank()) {
            return stores;
        }
        return stores.stream().filter(s -> s.getEmail().equalsIgnoreCase(email.trim())).toList();
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String source, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .data(data)
                .meta(ApiMeta.builder()
                        .requestId(UUID.randomUUID().toString())
                        .timestamp(Instant.now().toString())
                        .source(source)
                        .build())
                .build());
    }

    private ApiResponse<Map<String, Object>> error(String source, String message) {
        return ApiResponse.<Map<String, Object>>builder()
                .data(Map.of("error", message))
                .meta(ApiMeta.builder()
                        .requestId(UUID.randomUUID().toString())
                        .timestamp(Instant.now().toString())
                        .source(source)
                        .build())
                .build();
    }
}
