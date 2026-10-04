package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** PUT /program/{id}/letters/{key} — writing (or rewriting, unread) one of the program's letters. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramLetterRequest {

    @NotBlank(message = "a letter needs some words")
    @Size(max = 6000, message = "a letter must be at most 6000 characters")
    private String text;
}
