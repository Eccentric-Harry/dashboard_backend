package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A remembered grocery item — behind "Buy again" and the add bar's autocomplete. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingSuggestionDTO {
    private String name;
    private String category;
    private int count;
    private Instant lastAdded;
}
