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
import nl.martijndwars.webpush.Encoding;
import org.apache.http.Header;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import jakarta.annotation.PreDestroy;

@Slf4j
@Service
public class PushNotificationService {

    private final VapidKeyRepository vapidKeyRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;

    private String vapidPublicKey;
    private String vapidPrivateKey;

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    @PreDestroy
    public void cleanUp() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("Failed to close push http client", e);
        }
    }

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

            // Select encoding: use AES128GCM for modern endpoints, legacy AESGCM only for legacy GCM/Android endpoints
            Encoding encoding = Encoding.AES128GCM;
            if (sub.getEndpoint().contains("/gcm/send") || sub.getEndpoint().contains("android.googleapis.com")) {
                encoding = Encoding.AESGCM;
            }

            HttpPost post = pushService.preparePost(notification, encoding);

            // Strip trailing '=' padding from the Crypto-Key header if present to satisfy strict FCM formatting requirements
            Header cryptoKeyHeader = post.getFirstHeader("Crypto-Key");
            if (cryptoKeyHeader != null) {
                String val = cryptoKeyHeader.getValue();
                if (val.endsWith("=")) {
                    post.setHeader("Crypto-Key", val.substring(0, val.length() - 1));
                }
            }

            var response = httpClient.execute(post);
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode == 201) {
                log.debug("Push alert sent successfully to subscriber: {}", sub.getId());
            } else if (statusCode == 410 || statusCode == 404) {
                log.warn("Subscription expired or is invalid (Status {}), cleaning up database.", statusCode);
                pushSubscriptionRepository.delete(sub);
            } else {
                String responseBody = "";
                if (response.getEntity() != null) {
                    try {
                        responseBody = org.apache.http.util.EntityUtils.toString(response.getEntity());
                    } catch (Exception re) {
                        log.warn("Failed to read push response body", re);
                    }
                }
                log.error("Failed to send push notification, status code: {}, response: {}", statusCode, responseBody);
                if (statusCode == 403) {
                    log.warn("Received 403 Forbidden, cleaning up subscription to prevent infinite retry loop.");
                    pushSubscriptionRepository.delete(sub);
                }
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
