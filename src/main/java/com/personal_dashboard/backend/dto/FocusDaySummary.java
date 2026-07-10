package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Completed focus time for one day, grouped in Asia/Kolkata like the rest of
 * the app. Days without completed sessions are omitted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FocusDaySummary {

    /** ISO yyyy-MM-dd. */
    private String date;

    private long totalMinutes;

    private int sessions;
}
