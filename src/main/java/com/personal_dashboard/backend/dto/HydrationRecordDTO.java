package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HydrationRecordDTO {

    private String id;

    private String date;

    private Double waterIntakeMl;

    private Double targetMl;

    private Double progress;

    private String notes;
}
