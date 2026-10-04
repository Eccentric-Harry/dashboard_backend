package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/** A program photo or recording; {@link #dataUrl} is only filled when one item is fetched. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramMediaView {
    private String id;
    private String kind;
    private String label;
    private LocalDate date;
    private String mime;
    private Integer bytes;
    private Integer durationSec;
    private Instant createdAt;
    private String dataUrl;
}
