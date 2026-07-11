package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A single item externalized onto the Mind tab — a thought, a win, a gratitude note.
 * The whole point of the tab is that these live here instead of in the user's head,
 * so every user's entries stay strictly isolated (UserOwnedDocument).
 *
 * type:          THOUGHT | WIN | GRATITUDE | AFFIRMATION | REFLECTION | INTENTION
 * status:        OPEN | RESOLVED | PARKED | RELEASED | CONVERTED
 * valueTag:      Coding | Growth | Calm | Confidence | Devotion | Joy | Fulfilment
 * distortionTag: Catastrophizing | Mind-reading | All-or-nothing | Fortune-telling | Labeling
 *
 * Stored as free strings (not Java enums) to mirror the TypeScript union types in api.ts
 * and match how DailyTask stores itemType.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mind_entries")
@CompoundIndex(name = "user_date_idx", def = "{'userId': 1, 'date': 1}")
public class MindEntry implements UserOwnedDocument {

    @Id
    private String id;

    private String userId;

    private String type;

    private String text;

    private String reframedText;

    private String distortionTag;

    private String status;

    private String linkedTaskId;

    private String valueTag;

    private Boolean pinned;

    private LocalDate reviewDate;

    /**
     * True once this entry has ever been PARKED. Unlike reviewDate (cleared when a
     * parked worry resurfaces to OPEN), this never resets — it's the only durable
     * signal that a resolved/released/converted thought passed through the worry
     * parking lot, which the Mind Intelligence "most worry doesn't need action"
     * insight depends on.
     */
    private Boolean wasParked;

    private LocalDate date;

    @CreatedDate
    private Instant createdAt;

    private Instant resolvedAt;
}
