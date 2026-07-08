package com.personal_dashboard.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Outbound color mapping: local hex/category → Google colorId, and round-trip fidelity. */
class ColorMappingTest {

    // ── hexToGoogleColorId ────────────────────────────────────────────────────

    @Test
    void exactModernColorMapsToItsOwnId() {
        // Exact modern palette hex should map back to its own colorId.
        assertEquals("2",  GoogleCalendarClient.hexToGoogleColorId("#33b679")); // Sage
        assertEquals("9",  GoogleCalendarClient.hexToGoogleColorId("#3f51b5")); // Blueberry
        assertEquals("11", GoogleCalendarClient.hexToGoogleColorId("#d50000")); // Tomato
        assertEquals("7",  GoogleCalendarClient.hexToGoogleColorId("#039be5")); // Peacock
    }

    @Test
    void localCategoryColorsMapToSemanticGoogleColors() {
        // Personal violet → Grape (3)
        assertEquals("3", GoogleCalendarClient.hexToGoogleColorId("#7c3aed"));
        // Work blue → Blueberry (9)
        assertEquals("9", GoogleCalendarClient.hexToGoogleColorId("#2563eb"));
        // Health emerald → Sage (2)
        assertEquals("2", GoogleCalendarClient.hexToGoogleColorId("#10b981"));
        // Social pink → Flamingo (4)
        assertEquals("4", GoogleCalendarClient.hexToGoogleColorId("#db2777"));
        // Movies rose-red → Tangerine (6) — nearest by RGB distance, not Tomato
        assertEquals("6", GoogleCalendarClient.hexToGoogleColorId("#e11d48"));
    }

    @Test
    void hashPrefixIsOptional() {
        assertEquals(
            GoogleCalendarClient.hexToGoogleColorId("#33b679"),
            GoogleCalendarClient.hexToGoogleColorId("33b679")
        );
    }

    @Test
    void caseInsensitiveHex() {
        assertEquals(
            GoogleCalendarClient.hexToGoogleColorId("#33B679"),
            GoogleCalendarClient.hexToGoogleColorId("#33b679")
        );
    }

    @Test
    void nullAndBlankHexReturnsNull() {
        assertNull(GoogleCalendarClient.hexToGoogleColorId(null));
        assertNull(GoogleCalendarClient.hexToGoogleColorId(""));
        assertNull(GoogleCalendarClient.hexToGoogleColorId("   "));
    }

    @Test
    void malformedHexReturnsNull() {
        assertNull(GoogleCalendarClient.hexToGoogleColorId("#gg0000")); // invalid chars
        assertNull(GoogleCalendarClient.hexToGoogleColorId("#fff"));    // too short
    }

    @Test
    void roundTrip_inboundColorIdSurvivesOutbound() {
        // Modern hex values stored by getGoogleColorHex() inbound should round-trip
        // back to the same colorId on outbound.
        String[][] palette = {
            {"1", "#7986cb"}, {"2", "#33b679"}, {"3", "#8e24aa"},
            {"4", "#e67c73"}, {"5", "#f6bf26"}, {"6", "#f4511e"},
            {"7", "#039be5"}, {"8", "#616161"}, {"9", "#3f51b5"},
            {"10", "#0b8043"}, {"11", "#d50000"}
        };
        for (String[] pair : palette) {
            String expectedId = pair[0];
            String modernHex  = pair[1];
            assertEquals(expectedId, GoogleCalendarClient.hexToGoogleColorId(modernHex),
                    "Round-trip failed for colorId=" + expectedId + " hex=" + modernHex);
        }
    }

    // ── categoryToGoogleColorId ───────────────────────────────────────────────

    @Test
    void knownCategoriesMapped() {
        assertEquals("2",  GoogleCalendarClient.categoryToGoogleColorId("Health"));
        assertEquals("9",  GoogleCalendarClient.categoryToGoogleColorId("Work"));
        assertEquals("6",  GoogleCalendarClient.categoryToGoogleColorId("Finance"));
        assertEquals("10", GoogleCalendarClient.categoryToGoogleColorId("Learning"));
        assertEquals("4",  GoogleCalendarClient.categoryToGoogleColorId("Social"));
        assertEquals("11", GoogleCalendarClient.categoryToGoogleColorId("Movies"));
        assertEquals("3",  GoogleCalendarClient.categoryToGoogleColorId("Personal"));
    }

    @Test
    void categoryMappingIsCaseInsensitive() {
        assertEquals(
            GoogleCalendarClient.categoryToGoogleColorId("Health"),
            GoogleCalendarClient.categoryToGoogleColorId("HEALTH")
        );
    }

    @Test
    void unknownCategoryReturnsNull() {
        assertNull(GoogleCalendarClient.categoryToGoogleColorId("Hobbies"));
        assertNull(GoogleCalendarClient.categoryToGoogleColorId(null));
    }
}
