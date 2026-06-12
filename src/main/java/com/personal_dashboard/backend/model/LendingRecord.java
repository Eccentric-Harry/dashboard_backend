package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "lending_records")
public class LendingRecord {

    @Id
    private String id;

    private String borrower;

    private BigDecimal amount;

    private Instant date;

    private Instant dueDate;

    private String status; // "Pending" or "Repaid"

    private String notes;
}
