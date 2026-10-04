package com.personal_dashboard.backend.dto;

import com.personal_dashboard.backend.model.Program;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A {@link Program} as the client sees it: identical, except that a sealed letter comes back
 * without its text until the day it opens — the time capsule is kept by the server, not by
 * the UI politely looking away.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramView {

    private String id;
    private String title;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate birthday;
    private Double weightKg;
    private String liftPlace;
    private List<Program.Track> tracks;
    private Map<String, String> answers;
    private Map<String, LetterView> letters;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LetterView {
        /** True while the letter can't be read yet; {@link #text} is then null. */
        private boolean sealed;
        private LocalDate opensOn;
        private Instant writtenAt;
        private String text;
        /** Characters written, so a sealed letter can still say how long it is. */
        private int length;
    }
}
