package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "daily_financial_logs")
public class DailyFinancialLog {

    @Id
    private String id; // e.g., "2026-03-17"

    private Instant date;

    @Builder.Default
    private FinancialTotals dailyTotals = new FinancialTotals();

    /** Transactions grouped by category. Keys: "Food", "To Home", etc. */
    @Builder.Default
    private Map<String, List<FinancialTransaction>> transactions = new LinkedHashMap<>();
}
