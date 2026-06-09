package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.VapidKey;
import com.personal_dashboard.backend.repository.PushSubscriptionRepository;
import com.personal_dashboard.backend.repository.VapidKeyRepository;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import nl.martijndwars.webpush.Urgency;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.Security;
import java.util.Base64;

@Slf4j
@Service
public class PushNotificationService {

    private final VapidKeyRepository vapidKeyRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;

    private String vapidPublicKey;
    private String vapidPrivateKey;

    static {
        // Add BouncyCastle provider for EC Cryptography needed by VAPID
        Security.addProvider(new BouncyCastleProvider());
    }

    public PushNotificationService(VapidKeyRepository vapidKeyRepository,
                                   PushSubscriptionRepository pushSubscriptionRepository) {
        this.vapidKeyRepository = vapidKeyRepository;
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        initializeVapidKeys();
    }

    private void initializeVapidKeys() {
        var keys = vapidKeyRepository.findAll();
        if (keys.isEmpty()) {
            try {
                log.info("Generating new VAPID keypair...");
                
                // Generate prime256v1 EC KeyPair using BouncyCastle ECDH
                org.bouncycastle.jce.spec.ECParameterSpec parameterSpec = 
                        org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("prime256v1");
                java.security.KeyPairGenerator keyPairGenerator = java.security.KeyPairGenerator.getInstance("ECDH", "BC");
                keyPairGenerator.initialize(parameterSpec);
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                
                byte[] publicKeyBytes = Utils.encode((org.bouncycastle.jce.interfaces.ECPublicKey) keyPair.getPublic());
                byte[] privateKeyBytes = Utils.encode((org.bouncycastle.jce.interfaces.ECPrivateKey) keyPair.getPrivate());
                
                String pubKeyStr = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKeyBytes);
                String privKeyStr = Base64.getUrlEncoder().withoutPadding().encodeToString(privateKeyBytes);

                VapidKey newKey = VapidKey.builder()
                        .publicKey(pubKeyStr)
                        .privateKey(privKeyStr)
                        .build();
                vapidKeyRepository.save(newKey);

                this.vapidPublicKey = pubKeyStr;
                this.vapidPrivateKey = privKeyStr;
                log.info("VAPID keypair generated and saved to MongoDB.");
            } catch (Exception e) {
                log.error("Failed to generate VAPID keys", e);
                throw new RuntimeException("VAPID key generator failure", e);
            }
        } else {
            VapidKey key = keys.get(0);
            this.vapidPublicKey = key.getPublicKey();
            this.vapidPrivateKey = key.getPrivateKey();
            log.info("VAPID keys loaded successfully from MongoDB.");
        }
    }

    public String getPublicKey() {
        return this.vapidPublicKey;
    }

    public void sendPush(PushSubscription sub, String title, String message, String clickUrl) {
        try {
            Subscription webpushSubscription = new Subscription(
                    sub.getEndpoint(),
                    new Subscription.Keys(sub.getP256dh(), sub.getAuth())
            );

            // Construct JSON payload
            String payload = String.format("{\"title\":\"%s\",\"body\":\"%s\",\"url\":\"%s\"}",
                    escapeJson(title), escapeJson(message), escapeJson(clickUrl));

            Notification notification = new Notification(webpushSubscription, payload, Urgency.HIGH);

            PushService pushService = new PushService(
                    this.vapidPublicKey,
                    this.vapidPrivateKey,
                    "mailto:admin@personal-dashboard.com"
            );

            var response = pushService.send(notification);
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode == 201) {
                log.debug("Push alert sent successfully to subscriber: {}", sub.getId());
            } else if (statusCode == 410 || statusCode == 404) {
                log.warn("Subscription expired or is invalid (Status {}), cleaning up database.", statusCode);
                pushSubscriptionRepository.delete(sub);
            } else {
                log.error("Failed to send push notification, status code: {}", statusCode);
            }
        } catch (Exception e) {
            log.error("Error dispatching push notification to subscription " + sub.getId(), e);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
