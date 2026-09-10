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
 *                | BREATH | SPIRAL
 * status:        OPEN | RESOLVED | PARKED | RELEASED | CONVERTED | NOTICED | VERDICT_DUE
 * lane:          PROBLEM | WORRY | INTRUSIVE   (null on legacy / not-yet-triaged entries)
 * valueTag:      Coding | Growth | Calm | Confidence | Devotion | Joy | Fulfilment
 * distortionTag: Catastrophizing | Mind-reading | All-or-nothing | Fortune-telling | Labeling
 *
 * The lane matters more than it looks. A worry and an intrusive thought need opposite
 * handling — a worry benefits from being examined and predicted, an intrusive thought
 * gets worse every time it is examined — so the lane, not the text, decides which
 * actions the UI is even allowed to offer.
 *
 * Stored as free strings (not Java enums) to mirror the TypeScript union types in api.ts
 * and match how DailyTask stores itemType.
 */
@Data
@Builder(toBuilder = true)
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

    /**
     * Which of the three handling lanes this entry was triaged into. Null means it has
     * not been triaged yet (or predates triage), and the UI asks "what is this?" before
     * offering any action.
     */
    private String lane;

    /**
     * True when {@link #text} must never leave the server on a normal read.
     *
     * Intrusive thoughts may be typed out, but re-reading them is how the loop is fed,
     * so sealed text is stripped in MindService on every path except the explicit
     * sealed-archive endpoint. This is enforced server-side on purpose: making leakage
     * structurally impossible beats relying on every future caller to remember.
     */
    private Boolean textSealed;

    /** WORRY lane only — the feared outcome, its predicted likelihood, and the verdict. */
    private WorryPrediction prediction;

    /** INTRUSIVE lane only — coarse category, intensity, urge-decay result. */
    private IntrusiveMeta intrusive;

    /** type = SPIRAL only — the record of a Spiral Breaker session. */
    private SpiralLog spiral;

    private LocalDate reviewDate;

    /**
     * INTENTION (the Home "Today's anchor") only — freeform notes the user keeps
     * against the day's one thing: extra context, or how it actually went.
     */
    private String note;

    /**
     * INTENTION only — how the anchor landed: ACHIEVED | PARTIAL | MISSED.
     * Null until the user records an outcome.
     */
    private String outcome;

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
