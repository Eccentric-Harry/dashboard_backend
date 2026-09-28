package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "subscriptions")
public class Subscription implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String name;

    private BigDecimal cost;

    /**
     * Anchor due date — any one date the bill falls due on (local midnight, Asia/Kolkata).
     * Every other due date is this plus a whole number of intervals; the client derives
     * "next due" and "paid this cycle" from it and the linked payments.
     */
    private Instant billingDate;

    /** Category a payment is filed under. Null reads as "Subscriptions". */
    private String category;

    /** Billing interval unit: DAY | WEEK | MONTH | YEAR. Null reads as MONTH. */
    private String intervalUnit;

    /** Billing interval length in {@link #intervalUnit}s, e.g. 28 DAYs for a prepaid plan. Null reads as 1. */
    private Integer intervalCount;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
