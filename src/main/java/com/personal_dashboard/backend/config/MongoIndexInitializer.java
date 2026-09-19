package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.AuthToken;
import com.personal_dashboard.backend.model.DailyTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Creates the indexes the hot paths depend on. Spring Data's auto-index-creation is off
 * (its default), so model annotations alone create nothing — these are ensured explicitly.
 *
 * <ul>
 *   <li>{@code auth_tokens.token} — looked up by every request whose token isn't cached.</li>
 *   <li>{@code auth_tokens.expiresAt} TTL — Mongo purges tokens once they expire, so
 *       dead credentials don't accumulate (a login mints a new token each time).</li>
 *   <li>{@code daily_tasks (userId, date)} — the calendar/tasks/summary range reads.</li>
 * </ul>
 *
 * Creation is idempotent and fail-soft: an existing index with a conflicting definition
 * is logged and left alone rather than blocking startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoIndexInitializer {

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        ensure(AuthToken.class, new Index().on("token", Sort.Direction.ASC).unique().named("token_unique"));
        ensure(AuthToken.class, new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO).named("expiresAt_ttl"));
        ensure(DailyTask.class, new Index().on("userId", Sort.Direction.ASC).on("date", Sort.Direction.ASC).named("user_date_idx"));
    }

    private void ensure(Class<?> entity, Index index) {
        try {
            String name = mongoTemplate.indexOps(entity).createIndex(index);
            log.info("Ensured index {} on {}", name, mongoTemplate.getCollectionName(entity));
        } catch (RuntimeException e) {
            log.warn("Could not ensure index {} on {} — continuing without it: {}",
                    index.getIndexOptions().get("name"), mongoTemplate.getCollectionName(entity), e.getMessage());
        }
    }
}
