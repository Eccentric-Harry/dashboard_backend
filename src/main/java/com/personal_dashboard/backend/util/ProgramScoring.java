package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.model.Program;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure rules for {@link Program}s: the eight tracks a program starts with, and the two
 * questionnaires' scoring. Kept free of Spring so ProgramScoringTest can pin every case.
 */
public final class ProgramScoring {

    /** Track keys in display order. The client's content (program-content.ts) owns the words. */
    public static final List<String> TRACKS = List.of("run", "lift", "protein", "mood", "learn", "english", "screen", "regard");

    /** Protein that maxes out muscle gain from training (Morton et al. 2018), and the bad-day floor. */
    public static final double PROTEIN_G_PER_KG = 1.6;
    public static final double PROTEIN_FLOOR_G_PER_KG = 1.2;
    /** Used until a body weight is known (≈ 70 kg). */
    public static final double DEFAULT_PROTEIN_G = 110;
    public static final double DEFAULT_PROTEIN_FLOOR_G = 85;

    /** Rosenberg items 2, 5, 6, 8 and 9 (zero-based here) are worded negatively and scored in reverse. */
    private static final Set<Integer> ROSENBERG_REVERSED = Set.of(1, 4, 5, 7, 8);

    private ProgramScoring() {
    }

    /**
     * The tracks a new program starts with — the brief's targets. Screen has no number yet:
     * its cap is derived from the audit week until a review sets one.
     */
    public static List<Program.Track> defaultTracks(Double weightKg) {
        List<Program.Track> tracks = new ArrayList<>();
        for (String key : TRACKS) {
            Program.Track.TrackBuilder t = Program.Track.builder().key(key);
            switch (key) {
                case "run", "lift" -> t.target(3.0);
                case "protein" -> {
                    t.target(weightKg == null ? DEFAULT_PROTEIN_G : (double) Math.round(weightKg * PROTEIN_G_PER_KG));
                    t.floor(weightKg == null ? DEFAULT_PROTEIN_FLOOR_G : (double) Math.round(weightKg * PROTEIN_FLOOR_G_PER_KG));
                }
                case "learn" -> t.target(5.0);
                case "english" -> {
                    t.target(15.0);
                    t.floor(5.0);
                }
                case "regard" -> t.target(1.0);
                default -> {
                    // mood is observed, never targeted; screen's cap comes from the audit week.
                }
            }
            tracks.add(t.build());
        }
        return tracks;
    }

    /**
     * Rosenberg Self-Esteem Scale, 0–30. Each answer is how much the user agrees, as asked:
     * 0 strongly disagree … 3 strongly agree; negatively worded items are reversed here.
     */
    public static int rosenberg(List<Integer> answers) {
        if (answers == null || answers.size() != 10) {
            throw new IllegalArgumentException("The Rosenberg scale has 10 statements.");
        }
        int score = 0;
        for (int i = 0; i < 10; i++) {
            Integer a = answers.get(i);
            if (a == null || a < 0 || a > 3) {
                throw new IllegalArgumentException("Rosenberg answers are 0–3.");
            }
            score += ROSENBERG_REVERSED.contains(i) ? 3 - a : a;
        }
        return score;
    }

    /** WHO-5 Well-Being Index, 0–100: five answers 0 (at no time) – 5 (all of the time), summed × 4. */
    public static int who5(List<Integer> answers) {
        if (answers == null || answers.size() != 5) {
            throw new IllegalArgumentException("The WHO-5 has 5 statements.");
        }
        int raw = 0;
        for (Integer a : answers) {
            if (a == null || a < 0 || a > 5) {
                throw new IllegalArgumentException("WHO-5 answers are 0–5.");
            }
            raw += a;
        }
        return raw * 4;
    }
}
