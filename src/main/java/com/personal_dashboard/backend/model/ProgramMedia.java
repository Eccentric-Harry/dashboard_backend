package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Private media for a {@link Program}: progress photos (front, side) and the weekly English
 * speaking recordings. The bytes live in the document (photos are compressed client-side to
 * a few hundred KB, a 2-minute recording is under a megabyte) and are only ever returned to
 * their owner, one at a time, through the authenticated API — there is no public URL.
 * Listings project {@link #data} away.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "program_media")
public class ProgramMedia implements UserOwnedDocument {

    public static final String PHOTO = "PHOTO";
    public static final String AUDIO = "AUDIO";

    @Id
    private String id;

    private String userId;

    private String programId;

    /** PHOTO or AUDIO. */
    private String kind;

    /** front, side, or speech. */
    private String label;

    private LocalDate date;

    /** image/jpeg, audio/webm… (parameters stripped). */
    private String mime;

    private byte[] data;

    private Integer bytes;

    /** Recordings only. */
    private Integer durationSec;

    private Instant createdAt;
}
