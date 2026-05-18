package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "learnings")
public class Learning {

    @Id
    private String id;

    private String title;
    private String description;
    private String category;
    private LocalDate date;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
