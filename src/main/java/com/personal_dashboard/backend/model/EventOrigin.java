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
 * source     LOCAL        → created inside this dashboard.
 *            GOOGLE       → pulled in from a connected Google *Calendar* account.
 *                           Always an EVENT: a calendar entry is not a to-do.
 *            GOOGLE_TASKS → pulled in from a connected Google *Tasks* account.
 *                           Always a TASK, and the only inbound source that mints one.
 * accountId  For either GOOGLE source: the account email it was pulled from. Null for LOCAL.
 * calendarId For GOOGLE: the Google calendar id (currently always "primary").
 *            For GOOGLE_TASKS: the Google task-list id. Null for LOCAL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventOrigin {

    public static final String SOURCE_LOCAL = "LOCAL";
    public static final String SOURCE_GOOGLE = "GOOGLE";
    public static final String SOURCE_GOOGLE_TASKS = "GOOGLE_TASKS";

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

    public static EventOrigin googleTasks(String accountId, String taskListId) {
        return EventOrigin.builder()
                .source(SOURCE_GOOGLE_TASKS)
                .accountId(accountId)
                .calendarId(taskListId)
                .build();
    }

    /** True for Google <em>Calendar</em> only — {@link #isGoogleTasks()} is a separate source. */
    public boolean isGoogle() {
        return SOURCE_GOOGLE.equalsIgnoreCase(source);
    }

    public boolean isGoogleTasks() {
        return SOURCE_GOOGLE_TASKS.equalsIgnoreCase(source);
    }

    /** True for anything pulled out of Google, whichever API it came from. */
    public boolean isRemote() {
        return isGoogle() || isGoogleTasks();
    }

    public boolean isLocal() {
        return SOURCE_LOCAL.equalsIgnoreCase(source);
    }
}
