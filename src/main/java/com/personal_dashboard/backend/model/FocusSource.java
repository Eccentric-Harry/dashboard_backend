package com.personal_dashboard.backend.model;

/**
 * Where a focus session's minutes came from. Provenance is kept because the
 * three sources carry different evidential weight, and the insights layer is
 * allowed to say so:
 *
 * TIMER    → measured live by the in-app timer. Strongest evidence.
 * MANUAL   → entered after the fact by the user (office work, meetings away
 *            from the app). Self-reported but explicitly confirmed.
 * CALENDAR → derived from a timed calendar block the user reviewed and
 *            accepted. Never written without that confirmation — a booked
 *            block is not proof of focused work.
 */
public enum FocusSource {
    TIMER,
    MANUAL,
    CALENDAR
}
