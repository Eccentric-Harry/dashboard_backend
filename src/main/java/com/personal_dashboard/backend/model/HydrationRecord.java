package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hydration_records")
public class HydrationRecord {

    @Id
    private String id;

    private LocalDate date;

    private Double waterIntakeMl;

    private Double targetMl;

    private String notes;
}
