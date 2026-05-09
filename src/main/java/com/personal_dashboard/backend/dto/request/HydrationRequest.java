package com.personal_dashboard.backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HydrationRequest {

    private String date;

    private Double waterIntakeMl;

    private Double targetMl;

    private String notes;
}
