package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of a bulk category reclassify. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReclassifyResultDTO {
    /** Transactions moved or retyped. */
    private int updated;
    /** Net change applied to the running balance (non-zero only when a row's direction flipped). */
    private Double balanceDelta;
}
