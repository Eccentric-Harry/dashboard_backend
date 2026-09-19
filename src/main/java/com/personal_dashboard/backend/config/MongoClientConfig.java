package com.personal_dashboard.backend.config;

import com.mongodb.MongoClientSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Bounds every database operation. The driver defaults are unbounded exactly where it
 * hurts: no socket read timeout (a connection silently dropped by a NAT between Render
 * and Atlas hangs its request forever) and a 30 s server-selection wait. With these, a
 * database hiccup surfaces as a fast 503 that the client retries.
 *
 * <p>The pool keeps a few warm connections so a route's burst of parallel calls doesn't
 * queue behind fresh cross-region TLS handshakes (the driver opens only two at a time),
 * and recycles idle ones before a middlebox can drop them.
 *
 * <p>Runs before Boot's standard customizer (order 0), so any option set explicitly on
 * the connection string ({@code SPRING_MONGODB_URI}) still wins over these defaults.
 */
@Slf4j
@Component
public class MongoClientConfig implements MongoClientSettingsBuilderCustomizer, Ordered {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final long serverSelectionTimeoutMs;
    private final long poolMaxWaitMs;
    private final int poolMinSize;
    private final long poolMaxIdleMs;

    public MongoClientConfig(
            @Value("${app.mongo.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.mongo.read-timeout-ms:15000}") int readTimeoutMs,
            @Value("${app.mongo.server-selection-timeout-ms:8000}") long serverSelectionTimeoutMs,
            @Value("${app.mongo.pool.max-wait-ms:8000}") long poolMaxWaitMs,
            @Value("${app.mongo.pool.min-size:8}") int poolMinSize,
            @Value("${app.mongo.pool.max-idle-ms:240000}") long poolMaxIdleMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.serverSelectionTimeoutMs = serverSelectionTimeoutMs;
        this.poolMaxWaitMs = poolMaxWaitMs;
        this.poolMinSize = poolMinSize;
        this.poolMaxIdleMs = poolMaxIdleMs;
    }

    @Override
    public void customize(MongoClientSettings.Builder builder) {
        log.info("Mongo client bounds: connect={}ms read={}ms serverSelection={}ms poolWait={}ms poolMin={} poolMaxIdle={}ms",
                connectTimeoutMs, readTimeoutMs, serverSelectionTimeoutMs, poolMaxWaitMs, poolMinSize, poolMaxIdleMs);
        builder
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToClusterSettings(cluster -> cluster
                        .serverSelectionTimeout(serverSelectionTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(pool -> pool
                        .maxWaitTime(poolMaxWaitMs, TimeUnit.MILLISECONDS)
                        .minSize(poolMinSize)
                        .maxConnectionIdleTime(poolMaxIdleMs, TimeUnit.MILLISECONDS));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
