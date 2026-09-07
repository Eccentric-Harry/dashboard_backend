package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Coarse metadata for an INTRUSIVE-lane entry.
 *
 * Deliberately shallow. Intrusive thoughts are handled by noticing and letting pass,
 * not by analysis — detailed recording of their content is itself a mental compulsion,
 * so nothing here invites elaboration. The category is a fixed, blunt bucket and the
 * text (if any) is sealed on the entry itself.
 *
 * category: DOUBT | HARM | IMMORAL | UNNAMED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntrusiveMeta {

    private String category;

    /** 1-5, optional. */
    private Integer intensity;

    /** Seconds the user waited without checking, when the urge-decay timer was used. */
    private Integer urgeWaitedSeconds;

    /** Answer to "did it fade?" after waiting. Builds evidence that urges decay unaided. */
    private Boolean urgeFaded;
}
