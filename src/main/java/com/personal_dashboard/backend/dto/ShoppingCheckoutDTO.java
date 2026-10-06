package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The result of "Done shopping": what left the basket, and the ledger row written (null when no amount was given). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingCheckoutDTO {
    private List<ShoppingItemDTO> removed;
    private String transactionId;
}
