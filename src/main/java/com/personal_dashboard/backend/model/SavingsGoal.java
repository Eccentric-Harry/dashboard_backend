package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Something the user is saving for on /finance ("Saving for") — an iPhone, a trip, a
 * safety net. Distinct from {@code /goals} (weekly habits), hence the name.
 *
 * <p>Only the plan is stored. How much is saved is derived on read from ledger rows that
 * carry this goal's id ({@code FinancialTransaction.goalId}): Transfer OUT rows set money
 * aside, Transfer IN rows take it back out, an Expense row is the purchase. Same rule as
 * bills, whose "paid" is derived from payments carrying {@code subscriptionId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "savings_goals")
public class SavingsGoal implements UserOwnedDocument {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String BOUGHT = "BOUGHT";
    public static final String ARCHIVED = "ARCHIVED";

    @Id
    private String id;

    private String userId;

    private String name;

    /** PURCHASE | TRIP | SAFETY_NET | OPEN. */
    private String kind;

    /**
     * Icon key (features/finance/goal-icons.ts — Lucide, like the category icons): the label on
     * the envelope that makes the money feel spoken for. Keys are permanent once used.
     */
    private String icon;

    /** Palette key (sage, sky, heather, sand, clay, teal); null picks from board order. */
    private String color;

    /** What the goal needs in total. Null for OPEN ("just saving") goals. */
    private BigDecimal targetAmount;

    /** PURCHASE only: the sticker price and what knocks it down (old-phone exchange, card offer). */
    private BigDecimal listPrice;
    private BigDecimal exchangeValue;
    private BigDecimal cardOffer;

    /** Have it by this day (yyyy-MM-dd). Null = no deadline; the plan then runs on {@link #plannedMonthly}. */
    private String targetDate;

    /** What the user intends to set aside each month; the date is projected from it when there's no deadline. */
    private BigDecimal plannedMonthly;

    /** Where the money sits ("SBI savings", "HDFC RD", "Liquid fund"). A label — the app recommends nothing. */
    private String keptAt;

    /** First day of the plan (yyyy-MM-dd) — the start of the pace line. */
    private String startDate;

    /** Board order; lower comes first and is funded first when money is short. */
    private int priority;

    /** ACTIVE | PAUSED | BOUGHT | ARCHIVED (archive is the soft-delete). "Ready" is derived. */
    private String status;

    /** Set when the goal is bought: the day (yyyy-MM-dd) and what it really cost. */
    private String boughtOn;
    private BigDecimal boughtFor;

    /** Photos, highlights and the user's reasons — what it looks like. Managed by the showcase endpoints only. */
    private GoalShowcase showcase;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
