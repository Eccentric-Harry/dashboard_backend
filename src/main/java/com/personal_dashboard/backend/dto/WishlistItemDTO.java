package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A wishlist card as the client sees it. Money as plain numbers, like the other finance DTOs. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WishlistItemDTO {
    private String id;
    private String name;
    private String url;
    private String store;
    private String imageUrl;
    private Double price;
    private Double firstPrice;
    private Instant priceCheckedAt;
    private String priority;
    private String note;
    private String status;
    private String boughtOn;
    private Double boughtFor;
    private String transactionId;
    private String savingsGoalId;
    private Instant closedAt;
    private Instant createdAt;
}
