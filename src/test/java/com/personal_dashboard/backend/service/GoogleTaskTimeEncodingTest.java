package com.personal_dashboard.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Google task has no time field — the API rewrites a 13:00 due date to midnight — so the
 * time lives on the first line of the notes. That encoding has to survive being written and
 * read back indefinitely: a decode that missed would grow another "⏰ 13:00" line on every
 * single sync, forever.
 */
class GoogleTaskTimeEncodingTest {

    // ── encoding ───────────────────────────────────────────────────────────────

    @Test
    void putsTheTimeOnItsOwnFirstLineAboveTheRealNotes() {
        assertEquals("⏰ 09:15\n\nDraft a good intro about yourself",
                GoogleTaskTimeEncoding.encodeNotes("09:15", "Draft a good intro about yourself"));
    }

    @Test
    void withNoNotesTheTimeStandsAlone() {
        assertEquals("⏰ 13:00", GoogleTaskTimeEncoding.encodeNotes("13:00", null));
        assertEquals("⏰ 13:00", GoogleTaskTimeEncoding.encodeNotes("13:00", "   "));
    }

    @Test
    void anUntimedTaskIsNeverMarkedUp() {
        assertEquals("Draft a good intro", GoogleTaskTimeEncoding.encodeNotes(null, "Draft a good intro"));
        assertEquals("", GoogleTaskTimeEncoding.encodeNotes(null, null));
    }

    @Test
    void padsASingleDigitHour() {
        assertEquals("⏰ 09:05", GoogleTaskTimeEncoding.encodeNotes("9:05", null));
    }

    // ── round trip ─────────────────────────────────────────────────────────────

    @Test
    void roundTripsWithoutAccumulatingTimeLines() {
        String encoded = GoogleTaskTimeEncoding.encodeNotes("17:30", "aisle seat");
        GoogleTaskTimeEncoding.Decoded once = GoogleTaskTimeEncoding.decodeNotes(encoded);
        assertEquals("17:30", once.time());
        assertEquals("aisle seat", once.value());

        // Re-encoding what was decoded must reproduce the identical string, every time.
        assertEquals(encoded, GoogleTaskTimeEncoding.encodeNotes(once.time(), once.value()));
        assertEquals(encoded, GoogleTaskTimeEncoding.encodeNotes(
                GoogleTaskTimeEncoding.decodeNotes(encoded).time(),
                GoogleTaskTimeEncoding.decodeNotes(encoded).value()));
    }

    @Test
    void roundTripsWhenThereAreNoRealNotes() {
        String encoded = GoogleTaskTimeEncoding.encodeNotes("13:00", null);
        GoogleTaskTimeEncoding.Decoded d = GoogleTaskTimeEncoding.decodeNotes(encoded);
        assertEquals("13:00", d.time());
        assertNull(d.value());
        assertEquals(encoded, GoogleTaskTimeEncoding.encodeNotes(d.time(), d.value()));
    }

    @Test
    void editingTheMarkerOnThePhoneReschedulesTheTask() {
        GoogleTaskTimeEncoding.Decoded d = GoogleTaskTimeEncoding.decodeNotes("⏰ 14:00\n\naisle seat");
        assertEquals("14:00", d.time());
        assertEquals("aisle seat", d.value());
    }

    // ── not eating the user's own text ─────────────────────────────────────────

    @Test
    void plainNotesComeBackUntouched() {
        GoogleTaskTimeEncoding.Decoded d = GoogleTaskTimeEncoding.decodeNotes("Call the tailor at 5pm");
        assertNull(d.time());
        assertEquals("Call the tailor at 5pm", d.value());
    }

    @Test
    void aNoteThatMerelyMentionsATimeIsNotAMarker() {
        GoogleTaskTimeEncoding.Decoded d = GoogleTaskTimeEncoding.decodeNotes("13:00 is the deadline");
        assertNull(d.time());
        assertEquals("13:00 is the deadline", d.value());
    }

    @Test
    void rejectsImpossibleClockTimes() {
        GoogleTaskTimeEncoding.Decoded d = GoogleTaskTimeEncoding.decodeNotes("⏰ 25:00");
        assertNull(d.time());
        assertEquals("⏰ 25:00", d.value());
    }

    @Test
    void handlesNullsWithoutThrowing() {
        GoogleTaskTimeEncoding.Decoded d = GoogleTaskTimeEncoding.decodeNotes(null);
        assertNull(d.time());
        assertNull(d.value());
    }

    // ── legacy title prefix (read-only migration) ──────────────────────────────

    @Test
    void stripsTheLegacyTitlePrefixSoItCannotStickToTheStoredTitle() {
        // Tasks already in Google carry "13:00 · " on the title from the earlier scheme.
        // Without this, the next pull would write that prefix into the stored title, and
        // it would then be re-encoded on top of forever.
        GoogleTaskTimeEncoding.Decoded d =
                GoogleTaskTimeEncoding.stripLegacyTitlePrefix("13:00 · Book Tickets to Vijayawada");
        assertEquals("13:00", d.time());
        assertEquals("Book Tickets to Vijayawada", d.value());
    }

    @Test
    void leavesACleanTitleAlone() {
        GoogleTaskTimeEncoding.Decoded d = GoogleTaskTimeEncoding.stripLegacyTitlePrefix("Zustand Store");
        assertNull(d.time());
        assertEquals("Zustand Store", d.value());
    }

    @Test
    void doesNotEatTheStartOfLegitimateTitles() {
        GoogleTaskTimeEncoding.Decoded d =
                GoogleTaskTimeEncoding.stripLegacyTitlePrefix("10:00 - 11:00 sync with Rishitha");
        assertNull(d.time());
        assertEquals("10:00 - 11:00 sync with Rishitha", d.value());
    }
}
