package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.GoogleSyncStore;
import com.personal_dashboard.backend.repository.GoogleSyncStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleChannelMaintenanceJob {

    private final GoogleSyncStoreRepository syncStoreRepository;
    private final GoogleCalendarClient googleCalendarClient;

    /**
     * Webhook Subscription Renewal Scheduled Job
     * Runs daily at 1:00 AM to renew channel subscriptions expiring in the next 48 hours.
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void renewExpiringChannels() {
        log.info("Starting daily Google Calendar Webhook channel maintenance job");

        Instant threshold = Instant.now().plus(48, ChronoUnit.HOURS);
        List<GoogleSyncStore> allStores = syncStoreRepository.findAll();

        int renewedCount = 0;
        for (GoogleSyncStore store : allStores) {
            Instant expiration = store.getWebhookExpiration();
            
            if (expiration == null || expiration.isBefore(threshold)) {
                log.info("Renewing Google Calendar webhook subscription for user: {}. Expiration time: {}",
                        store.getUserId(), expiration);

                try {
                    String userId = store.getUserId();
                    
                    // Stop current channel if active
                    if (store.getWebhookChannelId() != null && store.getWebhookResourceId() != null) {
                        googleCalendarClient.stopChannel(userId, store.getWebhookChannelId(), store.getWebhookResourceId());
                    }

                    // Register new watch channel
                    String newChannelId = UUID.randomUUID().toString();
                    GoogleCalendarClient.WatchResponse watchResponse = googleCalendarClient.watchCalendar(userId, newChannelId);

                    // Update store with new subscription
                    store.setWebhookChannelId(watchResponse.getChannelId());
                    store.setWebhookResourceId(watchResponse.getResourceId());
                    store.setWebhookExpiration(watchResponse.getExpiration());
                    
                    syncStoreRepository.save(store);
                    renewedCount++;
                    log.info("Successfully renewed webhook channel. New expiration: {}", watchResponse.getExpiration());

                } catch (Exception e) {
                    log.error("Failed to renew Google Calendar webhook channel for user {}", store.getUserId(), e);
                }
            }
        }

        log.info("Finished Google Calendar Webhook channel maintenance job. Renewed {} channel(s).", renewedCount);
    }
}
