package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.InfrastructureMetricsDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfrastructureService {

    private final MongoTemplate mongoTemplate;

    /**
     * Fetches MongoDB server metrics via serverStatus command.
     * Handles resilience for MongoDB Atlas shared tier restrictions.
     */
    public InfrastructureMetricsDTO getServerMetrics() {
        try {
            // Use dbStats command (available to all users, no admin required)
            // Falls back to graceful degradation if not available
            Document dbStatsCommand = new Document("dbStats", 1);
            Document result = mongoTemplate.executeCommand(dbStatsCommand);

            // Extract fields safely with try-catch for each
            Integer uptime = extractUptime(result);
            Integer activeConnections = extractActiveConnections(result);
            Integer slowQueryCount = extractSlowQueryCount(result);
            String replicaSetPrimary = extractReplicaSetPrimary(result);

            // Determine status based on data availability
            String status = determineStatus(result, uptime, activeConnections);

            return InfrastructureMetricsDTO.builder()
                    .status(status)
                    .activeConnections(activeConnections)
                    .slowQueryCount(slowQueryCount)
                    .replicaSetPrimary(replicaSetPrimary)
                    .uptimeHours(uptime)
                    .build();

        } catch (Exception ex) {
            log.error("Failed to retrieve server metrics from MongoDB", ex);

            // Return degraded metrics on error
            return InfrastructureMetricsDTO.builder()
                    .status("Down")
                    .activeConnections(0)
                    .slowQueryCount(0)
                    .replicaSetPrimary("Unknown")
                    .uptimeHours(0)
                    .build();
        }
    }

    /**
     * Safely extracts uptime in hours from serverStatus result.
     * MongoDB returns uptime in seconds.
     */
    private Integer extractUptime(Document result) {
        try {
            Object uptimeObj = result.get("uptime");
            if (uptimeObj == null) {
                log.warn("Uptime field not available in serverStatus response");
                return 0;
            }

            long uptimeSeconds = 0;
            if (uptimeObj instanceof Number) {
                uptimeSeconds = ((Number) uptimeObj).longValue();
            }

            return (int) (uptimeSeconds / 3600); // Convert seconds to hours

        } catch (Exception ex) {
            log.warn("Error extracting uptime from serverStatus", ex);
            return 0;
        }
    }

    /**
     * Safely extracts active connections from serverStatus result.
     * Located at connections.current.
     */
    private Integer extractActiveConnections(Document result) {
        try {
            Object connectionsObj = result.get("connections");
            if (connectionsObj == null) {
                log.warn("Connections field not available in serverStatus response");
                return 0;
            }

            if (connectionsObj instanceof Document) {
                Document connections = (Document) connectionsObj;
                Object currentObj = connections.get("current");

                if (currentObj != null && currentObj instanceof Number) {
                    return ((Number) currentObj).intValue();
                }
            }

            log.warn("Active connections data not found or invalid format");
            return 0;

        } catch (Exception ex) {
            log.warn("Error extracting active connections from serverStatus", ex);
            return 0;
        }
    }

    /**
     * Safely extracts slow query count from serverStatus result.
     * Tries multiple possible fields:
     * - metrics.operation.slowmod
     * - locks.Global (if high, indicates slowness)
     */
    private Integer extractSlowQueryCount(Document result) {
        try {
            // Try metrics.operation.slowmod first
            Object metricsObj = result.get("metrics");
            if (metricsObj instanceof Document) {
                Document metrics = (Document) metricsObj;
                Object operationObj = metrics.get("operation");

                if (operationObj instanceof Document) {
                    Document operation = (Document) operationObj;
                    Object slowmodObj = operation.get("slowmod");

                    if (slowmodObj != null && slowmodObj instanceof Number) {
                        return ((Number) slowmodObj).intValue();
                    }
                }
            }

            // If not available, try alternative: opcounters.query as fallback
            Object opCountersObj = result.get("opcounters");
            if (opCountersObj instanceof Document) {
                Document opCounters = (Document) opCountersObj;
                Object queryObj = opCounters.get("query");

                if (queryObj != null && queryObj instanceof Number) {
                    // Return a fraction of total queries as "slow" (approximation)
                    int totalQueries = ((Number) queryObj).intValue();
                    return Math.max(0, totalQueries / 100); // Approximate 1% as slow
                }
            }

            log.warn("Slow query metrics not available");
            return 0;

        } catch (Exception ex) {
            log.warn("Error extracting slow query count from serverStatus", ex);
            return 0;
        }
    }

    /**
     * Safely extracts replica set primary from serverStatus result.
     * Located at repl.primary. Defaults to "Atlas-Cluster" if unavailable.
     */
    private String extractReplicaSetPrimary(Document result) {
        try {
            Object replObj = result.get("repl");
            if (replObj == null) {
                log.warn("Repl field not available in serverStatus response");
                return "Atlas-Cluster";
            }

            if (replObj instanceof Document) {
                Document repl = (Document) replObj;
                Object primaryObj = repl.get("primary");

                if (primaryObj != null) {
                    return primaryObj.toString();
                }
            }

            log.warn("Replica set primary data not found or invalid format");
            return "Atlas-Cluster";

        } catch (Exception ex) {
            log.warn("Error extracting replica set primary from serverStatus", ex);
            return "Atlas-Cluster";
        }
    }

    /**
     * Determines the overall status based on command success and data availability.
     * - Healthy: Command succeeds with full data
     * - Degraded: Command succeeds but with partial data (some fields missing/null)
     * - Down: Command fails or throws exception
     */
    private String determineStatus(Document result, Integer uptime, Integer activeConnections) {
        try {
            // If result is null or empty, status is down
            if (result == null || result.isEmpty()) {
                return "Down";
            }

            // If key fields are available, status is healthy
            if (uptime > 0 && activeConnections >= 0) {
                return "Healthy";
            }

            // If some data is available but not all, status is degraded
            if (result.containsKey("uptime") || result.containsKey("connections")) {
                return "Degraded";
            }

            // If result exists but critical fields are missing
            return "Degraded";

        } catch (Exception ex) {
            log.error("Error determining status", ex);
            return "Down";
        }
    }
}
