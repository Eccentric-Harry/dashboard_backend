package com.personal_dashboard.backend.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repairs embedded transactions whose id was stored under a literal {@code id} key.
 *
 * <p>Spring Data maps an embedded type's {@code id} property to the {@code _id} key, which is
 * what every transaction the app writes uses. An early bulk import (March–April 2026, 105 rows
 * in production) wrote {@code id} instead, so those rows deserialised with a <b>null id</b>:
 * they rendered in the ledger but could never be edited or deleted — the client had no id to
 * send. This renames the key, keeping the value, so the existing ids simply start resolving.
 * A row with neither key gets a fresh UUID.
 *
 * <p>Works on raw documents (the collection is tiny, ~one document per day), so it bypasses
 * the user-scoping entity listener deliberately — there is no user context at startup.
 * Idempotent: a second run finds nothing to rename. Fail-soft, like
 * {@link CalendarEventItemTypeMigration}: a migration must never take startup down.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinanceTransactionIdMigration {

    static final String COLLECTION = "daily_financial_logs";

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void repairTransactionIds() {
        try {
            MongoCollection<Document> logs = mongoTemplate.getCollection(COLLECTION);
            int repairedRows = 0;
            int touchedDays = 0;
            for (Document dailyLog : logs.find(Filters.exists("transactions"))) {
                Object transactions = dailyLog.get("transactions");
                if (!(transactions instanceof Document byCategory)) continue;
                int repaired = repairIds(byCategory);
                if (repaired == 0) continue;
                logs.updateOne(Filters.eq("_id", dailyLog.get("_id")), Updates.set("transactions", byCategory));
                repairedRows += repaired;
                touchedDays++;
            }
            if (repairedRows > 0) {
                log.info("Finance id migration: renamed id → _id on {} transaction(s) across {} day(s); "
                        + "they can now be edited and deleted.", repairedRows, touchedDays);
            } else {
                log.debug("Finance id migration: nothing to repair.");
            }
        } catch (RuntimeException e) {
            log.warn("Finance id migration failed — continuing startup without it: {}", e.getMessage());
        }
    }

    /**
     * Renames {@code id} → {@code _id} on every embedded transaction that lacks {@code _id}.
     * Mutates {@code byCategory} in place and returns how many rows changed.
     */
    static int repairIds(Document byCategory) {
        int repaired = 0;
        for (Map.Entry<String, Object> bucket : byCategory.entrySet()) {
            if (!(bucket.getValue() instanceof List<?> rows)) continue;
            for (Object row : rows) {
                if (!(row instanceof Document tx) || tx.containsKey("_id")) continue;
                Object legacyId = tx.remove("id");
                tx.put("_id", legacyId != null ? legacyId.toString() : UUID.randomUUID().toString());
                repaired++;
            }
        }
        return repaired;
    }
}
