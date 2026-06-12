package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LendingRecordDTO {

    private String id;

    private String borrower;

    private Double amount;

    private String date;

    private String dueDate;

    private String status;

    private String notes;
}
