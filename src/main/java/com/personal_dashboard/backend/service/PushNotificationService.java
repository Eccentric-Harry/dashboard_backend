package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.model.PushSubscription;
import com.personal_dashboard.backend.model.VapidKey;
import com.personal_dashboard.backend.repository.VapidKeyRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import nl.martijndwars.webpush.Urgency;
import nl.martijndwars.webpush.Utils;
import org.apache.http.Header;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.KeyPair;
import java.security.Security;
import java.util.Base64;
import java.util.Map;

/**
 * Web Push transport. Owns the VAPID keypair and one HTTP client, and reports every
 * send as a typed {@link PushOutcome} instead of swallowing failures.
 *
 * <p>That distinction is the whole point: the caller has to be able to tell "this device
 * is gone, stop pushing to it" from "the push service is busy, try again" from "we sent
 * it". The old version logged all three the same way and its caller had already recorded
 * the notification as sent before finding out.
 */
@Slf4j
@Service
public class PushNotificationService {

    /** How a push attempt ended, from the caller's point of view. */
    public enum Kind {
        /** The push service took the payload. Delivery to the device is now its problem. */
        ACCEPTED,
        /** The endpoint is dead (404/410). Deactivate the subscription; never retry. */
        EXPIRED,
        /** Transient (429/5xx/network). Safe to retry this endpoint later. */
        RETRYABLE,
        /** Rejected for a reason a retry cannot fix (400/403/413). Do not retry. */
        PERMANENT
    }

    public record PushOutcome(Kind kind, Integer statusCode, String message) {
        public boolean accepted() {
            return kind == Kind.ACCEPTED;
        }

        static PushOutcome of(Kind kind, Integer statusCode, String message) {
            return new PushOutcome(kind, statusCode, message);
        }
    }

    static {
        // BouncyCastle supplies the EC crypto VAPID needs.
        Security.addProvider(new BouncyCastleProvider());
    }

    private final VapidKeyRepository vapidKeyRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CloseableHttpClient httpClient = HttpClients.createDefault();
    private final String vapidSubject;

    private volatile String vapidPublicKey;
    private volatile String vapidPrivateKey;
    private volatile PushService pushService;

    public PushNotificationService(VapidKeyRepository vapidKeyRepository,
                                   @Value("${notifications.vapid-subject:mailto:admin@personal-dashboard.com}") String vapidSubject) {
        this.vapidKeyRepository = vapidKeyRepository;
        this.vapidSubject = vapidSubject;
    }

    @PreDestroy
    public void cleanUp() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("Failed to close push http client", e);
        }
    }

    public String getPublicKey() {
        ensureKeys();
        return vapidPublicKey;
    }

    /**
     * Keys are resolved on first use rather than in the constructor: a Mongo hiccup at
     * boot used to take the whole application down with it, and every other subsystem
     * here starts fine without push.
     */
    private synchronized void ensureKeys() {
        if (vapidPublicKey != null && vapidPrivateKey != null) {
            return;
        }
        var keys = vapidKeyRepository.findAll();
        if (!keys.isEmpty()) {
            VapidKey key = keys.get(0);
            this.vapidPublicKey = key.getPublicKey();
            this.vapidPrivateKey = key.getPrivateKey();
            log.info("VAPID keys loaded from MongoDB.");
            return;
        }
        try {
            log.info("Generating new VAPID keypair...");
            org.bouncycastle.jce.spec.ECParameterSpec parameterSpec =
                    org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("prime256v1");
            java.security.KeyPairGenerator keyPairGenerator =
                    java.security.KeyPairGenerator.getInstance("ECDH", "BC");
            keyPairGenerator.initialize(parameterSpec);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            byte[] publicKeyBytes = Utils.encode((org.bouncycastle.jce.interfaces.ECPublicKey) keyPair.getPublic());
            byte[] privateKeyBytes = Utils.encode((org.bouncycastle.jce.interfaces.ECPrivateKey) keyPair.getPrivate());

            String pubKeyStr = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKeyBytes);
            String privKeyStr = Base64.getUrlEncoder().withoutPadding().encodeToString(privateKeyBytes);

            vapidKeyRepository.save(VapidKey.builder().publicKey(pubKeyStr).privateKey(privKeyStr).build());
            this.vapidPublicKey = pubKeyStr;
            this.vapidPrivateKey = privKeyStr;
            log.info("VAPID keypair generated and saved to MongoDB.");
        } catch (Exception e) {
            throw new IllegalStateException("VAPID key generator failure", e);
        }
    }

    private PushService pushService() {
        ensureKeys();
        PushService local = pushService;
        if (local == null) {
            synchronized (this) {
                local = pushService;
                if (local == null) {
                    try {
                        local = new PushService(vapidPublicKey, vapidPrivateKey, vapidSubject);
                        pushService = local;
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not build the VAPID push service", e);
                    }
                }
            }
        }
        return local;
    }

    /** Serialises the payload the service worker will receive. */
    public String buildPayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // Unreachable for a plain string map, but a broken payload must not look like a send failure.
            throw new IllegalArgumentException("Could not serialise push payload", e);
        }
    }

    /**
     * Sends one payload to one device. Never throws — the outcome is the return value, so
     * the dispatcher can record per-device state without a try/catch around business logic.
     */
    public PushOutcome send(PushSubscription sub, String payloadJson) {
        try {
            Subscription webpushSubscription = new Subscription(
                    sub.getEndpoint(),
                    new Subscription.Keys(sub.getP256dh(), sub.getAuth()));

            Notification notification = new Notification(webpushSubscription, payloadJson, Urgency.HIGH);

            // Modern endpoints speak aes128gcm; only the legacy GCM bridge still needs aesgcm.
            Encoding encoding = Encoding.AES128GCM;
            String endpoint = sub.getEndpoint() == null ? "" : sub.getEndpoint();
            if (endpoint.contains("/gcm/send") || endpoint.contains("android.googleapis.com")) {
                encoding = Encoding.AESGCM;
            }

            HttpPost post = pushService().preparePost(notification, encoding);

            // FCM rejects a padded Crypto-Key header.
            Header cryptoKeyHeader = post.getFirstHeader("Crypto-Key");
            if (cryptoKeyHeader != null) {
                String val = cryptoKeyHeader.getValue();
                if (val.endsWith("=")) {
                    post.setHeader("Crypto-Key", val.substring(0, val.length() - 1));
                }
            }

            try (var response = httpClient.execute(post)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String body = readBody(response);
                return classify(statusCode, body);
            }
        } catch (IOException e) {
            // Network-level: the push service may well have taken it, but we cannot know.
            // Retryable, and the per-endpoint delivery record keeps a repeat from duplicating.
            return PushOutcome.of(Kind.RETRYABLE, null, "transport: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected failure preparing push for subscription {}", sub.getId(), e);
            return PushOutcome.of(Kind.PERMANENT, null, "encode: " + e.getMessage());
        }
    }

    static PushOutcome classify(int statusCode, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return PushOutcome.of(Kind.ACCEPTED, statusCode, null);
        }
        if (statusCode == 404 || statusCode == 410) {
            return PushOutcome.of(Kind.EXPIRED, statusCode, "endpoint gone");
        }
        if (statusCode == 429 || statusCode >= 500) {
            return PushOutcome.of(Kind.RETRYABLE, statusCode, truncate(body));
        }
        // 400 (malformed), 403 (VAPID mismatch), 413 (payload too large) — retrying changes nothing.
        return PushOutcome.of(Kind.PERMANENT, statusCode, truncate(body));
    }

    private String readBody(org.apache.http.HttpResponse response) {
        try {
            return response.getEntity() != null
                    ? org.apache.http.util.EntityUtils.toString(response.getEntity())
                    : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String body) {
        if (body == null || body.isBlank()) return null;
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
