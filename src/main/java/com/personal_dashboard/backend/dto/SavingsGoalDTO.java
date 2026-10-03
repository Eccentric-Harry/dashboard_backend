package com.personal_dashboard.backend.dto;

import com.personal_dashboard.backend.model.GoalShowcase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A savings goal's plan plus its money, derived from the ledger on read. The client works
 * out pace, the per-payday figure and the projected date from these (lib/finance-goals.ts).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavingsGoalDTO {
    private String id;
    private String name;
    private String kind;
    private String icon;
    private String color;
    private Double targetAmount;
    private Double listPrice;
    private Double exchangeValue;
    private Double cardOffer;
    private String targetDate;
    private Double plannedMonthly;
    private String keptAt;
    private String startDate;
    private Integer priority;
    private String status;
    private String boughtOn;
    private Double boughtFor;
    /** Null until photos are found or added. */
    private GoalShowcase showcase;

    /** Still set aside: total set aside − taken out. */
    private Double saved;
    private Double setAside;
    private Double takenOut;
    /** Purchases paid for from this goal. */
    private Double spent;
    /** Number of set-aside rows. */
    private Integer contributions;
    private String firstContributionDate;
    private String lastContributionDate;
}
