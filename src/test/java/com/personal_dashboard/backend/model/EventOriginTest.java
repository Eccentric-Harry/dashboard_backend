package com.personal_dashboard.backend.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventOriginTest {

    @Test
    void local_marksSourceLocalWithNoAccount() {
        EventOrigin origin = EventOrigin.local();

        assertEquals(EventOrigin.SOURCE_LOCAL, origin.getSource());
        assertNull(origin.getAccountId());
        assertNull(origin.getCalendarId());
        assertTrue(origin.isLocal());
        assertFalse(origin.isGoogle());
    }

    @Test
    void google_capturesAccountAndCalendar() {
        EventOrigin origin = EventOrigin.google("user@gmail.com", "primary");

        assertEquals(EventOrigin.SOURCE_GOOGLE, origin.getSource());
        assertEquals("user@gmail.com", origin.getAccountId());
        assertEquals("primary", origin.getCalendarId());
        assertTrue(origin.isGoogle());
        assertFalse(origin.isLocal());
    }

    @Test
    void isChecks_areCaseInsensitive() {
        EventOrigin origin = EventOrigin.builder().source("google").build();
        assertTrue(origin.isGoogle());
        assertFalse(origin.isLocal());
    }
}
