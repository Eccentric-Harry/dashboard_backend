package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What a savings goal looks like — the photos and lines that keep the thing itself in view
 * while the money builds. A visual reminder of what the money is for measurably lifts how
 * much people save (Soman &amp; Cheema 2011), and it's why Monzo pots and Qapital goals carry
 * a picture. Embedded in {@link SavingsGoal}; never touched by the plan's PUT.
 *
 * <p>Photos are hotlinked from where they were found, not copied: the page stays the
 * source, and Atlas M0's 512 MB stays for data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalShowcase {

    /** The product page the photos and highlights came from. Null when every photo was added by link. */
    private String sourceUrl;

    /** Its host without "www." ("apple.com"), for the credit line. */
    private String sourceName;

    /** The page's own title ("iPhone 18 Pro and iPhone 18 Pro Max"). */
    private String title;

    /** Short lines from the page's description ("48MP Main camera with variable aperture"). */
    @Builder.Default
    private List<String> highlights = new ArrayList<>();

    /** In the user's order; the first is the cover. */
    @Builder.Default
    private List<GoalPhoto> photos = new ArrayList<>();

    /** The user's own words for why they want it — kept when the photos are refetched. */
    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    private Instant fetchedAt;
}
