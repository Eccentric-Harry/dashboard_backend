package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Immutable record of where an event was born — its "source of truth".
 *
 * Once set on a DailyTask it must never be mutated: it drives loop-prevention
 * during outbound sync (a GOOGLE-origin event must not be pushed back to the
 * account it came from) and dedup precedence during pull.
 *
 * source     LOCAL  → created inside this dashboard.
 *            GOOGLE → pulled in from a connected Google Calendar account.
 * accountId  For GOOGLE: the calendarEmail it was pulled from. For LOCAL: null.
 * calendarId The Google calendar id (currently always "primary"). Null for LOCAL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventOrigin {

    public static final String SOURCE_LOCAL = "LOCAL";
    public static final String SOURCE_GOOGLE = "GOOGLE";

    private String source;
    private String accountId;
    private String calendarId;

    public static EventOrigin local() {
        return EventOrigin.builder().source(SOURCE_LOCAL).build();
    }

    public static EventOrigin google(String accountId, String calendarId) {
        return EventOrigin.builder()
                .source(SOURCE_GOOGLE)
                .accountId(accountId)
                .calendarId(calendarId)
                .build();
    }

    public boolean isGoogle() {
        return SOURCE_GOOGLE.equalsIgnoreCase(source);
    }

    public boolean isLocal() {
        return SOURCE_LOCAL.equalsIgnoreCase(source);
    }
}
