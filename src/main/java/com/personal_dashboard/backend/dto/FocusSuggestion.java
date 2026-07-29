package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A calendar block that looks like it held focused work, offered for the user
 * to confirm. Deliberately a *suggestion* and not an auto-import: a booked
 * block is evidence that time was reserved, not that it was spent focused, and
 * silently converting one into the other would repeat the very mistake this
 * feature exists to fix (treating an assumption as a measurement).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FocusSuggestion {

    /** Calendar occurrence id — the dedupe key once imported. */
    private String occurrenceId;

    /** ISO yyyy-MM-dd. */
    private String date;

    private String title;

    private String startTime;

    private String endTime;

    private int minutes;

    private String category;

    /** LOCAL for native calendar items, GOOGLE for synced ones. */
    private String origin;
}
