package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Polls every account with Google Tasks sync enabled.
 *
 * <p>This job exists because the Tasks API has no equivalent of Calendar's watch
 * channels — there is no way to be told that something changed, so the only way to
 * learn about a task ticked off on the phone is to ask. The interval is the whole
 * design trade-off: shorter means the dashboard catches up sooner, longer means fewer
 * calls against a 50,000/day quota. Two minutes against the shared list is a couple of
 * thousand calls a day, comfortably inside it.
 *
 * <p>This is the safety net, not the main path. What the user actually notices is the
 * on-demand refresh the app fires when it opens its tasks view
 * ({@code POST /google-tasks/refresh}); this timer is what catches changes while nobody
 * is looking, so the dashboard is already correct by the time they do look.
 *
 * <p>One account failing never stops the others — each is polled in its own try block,
 * exactly as the notification dispatcher treats one device's failure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleTasksPollJob {

    private final GoogleSyncStoreRepository syncStoreRepository;
    private final GoogleTasksSyncService tasksSyncService;

    @Value("${google.tasks.poll-enabled:true}")
    private boolean pollEnabled;

    @Scheduled(fixedDelayString = "${google.tasks.poll-interval-ms:120000}",
               initialDelayString = "${google.tasks.poll-initial-delay-ms:60000}")
    public void poll() {
        if (!pollEnabled) {
            return;
        }
        List<GoogleSyncStore> stores = syncStoreRepository.findByTasksSyncEnabledTrue();
        if (stores.isEmpty()) {
            return;
        }
        log.debug("Google Tasks poll: {} account(s)", stores.size());
        for (GoogleSyncStore store : stores) {
            if (store.isDisconnected()) {
                continue;
            }
            try {
                tasksSyncService.pollAccount(store.getUserId(), store.getEmail());
            } catch (Exception e) {
                log.error("Google Tasks poll failed for {}: {}", store.getEmail(), e.getMessage());
            }
        }
    }
}
