package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Accept a set of calendar-derived focus suggestions.
 *
 * The client sends back only the occurrenceIds it wants; the server re-derives
 * every block's title and duration from the calendar rather than trusting
 * client-supplied minutes, so an import can never inflate a day's focus time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FocusImportRequest {

    @NotEmpty(message = "At least one occurrenceId is required")
    private List<String> occurrenceIds;

    /** Window to re-derive the accepted blocks from. */
    private String startDate;

    private String endDate;
}
