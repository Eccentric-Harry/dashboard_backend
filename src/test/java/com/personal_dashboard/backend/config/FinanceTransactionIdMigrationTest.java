package com.personal_dashboard.backend.config;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinanceTransactionIdMigrationTest {

    @Test
    void renamesLegacyIdKeyAndKeepsTheValue() {
        Document legacy = new Document("id", "69e38b70a8176ec38be3e360").append("description", "Snacks");
        Document byCategory = new Document("Food", new ArrayList<>(List.of(legacy)));

        assertEquals(1, FinanceTransactionIdMigration.repairIds(byCategory));
        assertEquals("69e38b70a8176ec38be3e360", legacy.get("_id"));
        assertFalse(legacy.containsKey("id"));
        assertEquals("Snacks", legacy.get("description"));
    }

    @Test
    void leavesRowsWrittenByTheAppAloneAndIsIdempotent() {
        Document current = new Document("_id", "abc").append("description", "Rent");
        Document legacy = new Document("id", "def");
        Document byCategory = new Document("Bills", new ArrayList<>(List.of(current)))
                .append("Food", new ArrayList<>(List.of(legacy)));

        assertEquals(1, FinanceTransactionIdMigration.repairIds(byCategory));
        assertEquals(0, FinanceTransactionIdMigration.repairIds(byCategory));
        assertEquals("abc", current.get("_id"));
        assertEquals("def", legacy.get("_id"));
    }

    @Test
    void mintsAnIdWhenARowHasNeither() {
        Document orphan = new Document("description", "Unknown");
        Document byCategory = new Document("Miscellaneous", new ArrayList<>(List.of(orphan)));

        assertEquals(1, FinanceTransactionIdMigration.repairIds(byCategory));
        assertNotNull(orphan.getString("_id"));
    }
}
