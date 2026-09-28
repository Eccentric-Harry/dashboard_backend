package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionDTO {
    private String id;
    private String name;
    private BigDecimal cost;
    /** Anchor due date as a local ISO date (yyyy-MM-dd), or null when never set. */
    private String billingDate;
    /** Category payments are filed under (defaults applied). */
    private String category;
    /** DAY | WEEK | MONTH | YEAR (defaults applied). */
    private String intervalUnit;
    private Integer intervalCount;
    /** Cost normalised to one month, so bills on different cycles can be summed. */
    private Double monthlyCost;
}
