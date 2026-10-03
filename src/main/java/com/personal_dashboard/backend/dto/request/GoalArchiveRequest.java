package com.personal_dashboard.backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Archives (soft-deletes) a goal; {@code release} first moves anything still in it back to the balance. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalArchiveRequest {
    private boolean release;
}
