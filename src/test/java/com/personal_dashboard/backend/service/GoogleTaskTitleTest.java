package com.personal_dashboard.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The title prefix is the only place a time of day can live on a Google task, so its
 * encoding has to survive being written and read back indefinitely. A decode that misses
 * would grow "13:00 · 13:00 · Lunch" a little more on every sync.
 */
class GoogleTaskTitleTest {

    @Test
    void encodesTheTimeAsAVisiblePrefix() {
        assertEquals("13:00 · Book Tickets to Vijayawada",
                GoogleTaskTitle.encode("13:00", "Book Tickets to Vijayawada"));
    }

    @Test
    void leavesUntimedTasksAlone() {
        assertEquals("Zustand Store", GoogleTaskTitle.encode(null, "Zustand Store"));
        assertEquals("Zustand Store", GoogleTaskTitle.encode("", "Zustand Store"));
    }

    @Test
    void padsASingleDigitHour() {
        assertEquals("09:05 · Standup", GoogleTaskTitle.encode("9:05", "Standup"));
    }

    @Test
    void roundTripsWithoutAccumulatingPrefixes() {
        String encoded = GoogleTaskTitle.encode("17:30", "Pick your Blazer from CandidMen");
        GoogleTaskTitle.Decoded once = GoogleTaskTitle.decode(encoded);
        assertEquals("17:30", once.time());
        assertEquals("Pick your Blazer from CandidMen", once.title());

        // Re-encoding what we decoded must produce the identical string, forever.
        assertEquals(encoded, GoogleTaskTitle.encode(once.time(), once.title()));
    }

    @Test
    void decodingAnEditedPrefixReschedulesTheTask() {
        // The feature this buys: changing the prefix on the phone changes the time here.
        GoogleTaskTitle.Decoded d = GoogleTaskTitle.decode("14:00 · Book Tickets to Vijayawada");
        assertEquals("14:00", d.time());
        assertEquals("Book Tickets to Vijayawada", d.title());
    }

    @Test
    void aPlainTitleIsReturnedUntouched() {
        GoogleTaskTitle.Decoded d = GoogleTaskTitle.decode("Buy milk");
        assertNull(d.time());
        assertEquals("Buy milk", d.title());
    }

    @Test
    void doesNotEatTheStartOfLegitimateTitles() {
        // Without the separator this is just a title that happens to start with a time.
        GoogleTaskTitle.Decoded d = GoogleTaskTitle.decode("10:00 - 11:00 sync with Rishitha");
        assertNull(d.time());
        assertEquals("10:00 - 11:00 sync with Rishitha", d.title());
    }

    @Test
    void rejectsImpossibleClockTimes() {
        GoogleTaskTitle.Decoded d = GoogleTaskTitle.decode("25:00 · not a time");
        assertNull(d.time());
        assertEquals("25:00 · not a time", d.title());
    }

    @Test
    void aPrefixWithNoTitleAfterItIsTreatedAsTheTitle() {
        GoogleTaskTitle.Decoded d = GoogleTaskTitle.decode("13:00 ·");
        assertNull(d.time());
        assertEquals("13:00 ·", d.title());
    }

    @Test
    void handlesNullsWithoutThrowing() {
        assertEquals("Untitled", GoogleTaskTitle.encode("13:00", null).substring(8));
        GoogleTaskTitle.Decoded d = GoogleTaskTitle.decode(null);
        assertNull(d.time());
        assertNull(d.title());
    }
}
