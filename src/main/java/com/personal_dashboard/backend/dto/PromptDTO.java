package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptDTO {
    private String id;
    private String title;
    private String content;
    private String category;
    private List<String> tags;
    private Instant createdAt;
    private Instant updatedAt;
}
