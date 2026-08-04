package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.model.MealEntry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One place that turns a meal's quality marking into a comparable number.
 *
 * <p>Quality reaches us in two shapes, both still present in the collection: words
 * ("excellent"/"fair") on older records, and letter grades (A/B/C/D) plus
 * {@code recompositionAssessment.letter_grade} on anything the AI pipeline wrote. The
 * frontend already normalises both (see {@code meal-grade.ts}); this mirrors that ramp
 * server-side so per-day aggregates can be computed while the day's meals are already
 * in hand, instead of shipping every meal to the client to be counted there.
 *
 * <p>Points are GPA-shaped — A=4 down to D=1 — so a day's meals average cleanly and the
 * average maps back to a letter. E/F collapse into D, matching the frontend.
 */
public final class MealGrades {

    private MealGrades() {
    }

    public static final int POINTS_A = 4;
    public static final int POINTS_D = 1;

    /** Word gradings, lowercased, as written by the pre-letter-grade pipeline. */
    private static final Map<String, Integer> WORD_POINTS = Map.ofEntries(
            Map.entry("excellent", 4),
            Map.entry("great", 4),
            Map.entry("good", 3),
            Map.entry("balanced", 3),
            Map.entry("fair", 2),
            Map.entry("average", 2),
            Map.entry("moderate", 2),
            Map.entry("poor", 1),
            Map.entry("bad", 1),
            Map.entry("unhealthy", 1));

    /**
     * Points for a single meal, or null when the meal carries no usable grading —
     * a manually logged entry, or one whose analysis never completed. Ungraded meals
     * must stay distinguishable from bad ones, so callers can average over what was
     * actually assessed rather than reading silence as a low score.
     *
     * <p>Precedence matches {@code gradeFromEntry} on the client: the AI letter grade
     * wins, then the AI's word grade, then the top-level {@code mealQuality} field.
     */
    public static Integer pointsOf(MealEntry entry) {
        if (entry == null) {
            return null;
        }
        Map<String, Object> assessment = entry.getRecompositionAssessment();
        if (assessment != null) {
            Integer fromLetter = pointsOf(asString(assessment.get("letter_grade")));
            if (fromLetter != null) {
                return fromLetter;
            }
            Integer fromWord = pointsOf(asString(assessment.get("meal_quality")));
            if (fromWord != null) {
                return fromWord;
            }
        }
        return pointsOf(entry.getMealQuality());
    }

    /**
     * Points for a raw grading string, tolerating suffixes ("A+", "b-", "A grade"),
     * or null when the value means nothing on this scale.
     */
    public static Integer pointsOf(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim().toLowerCase();
        if (clean.isEmpty()) {
            return null;
        }

        char head = clean.charAt(0);
        boolean letterGrade = head >= 'a' && head <= 'f'
                && (clean.length() == 1 || !Character.isLetter(clean.charAt(1)));
        if (letterGrade) {
            return switch (head) {
                case 'a' -> 4;
                case 'b' -> 3;
                case 'c' -> 2;
                // E and F collapse into the lowest tier rather than extending the ramp.
                default -> 1;
            };
        }

        return WORD_POINTS.get(clean);
    }

    /** The letter an averaged points value rounds to, or null when there is nothing to round. */
    public static String letterFor(Double averagePoints) {
        if (averagePoints == null) {
            return null;
        }
        long rounded = Math.round(Math.max(POINTS_D, Math.min(POINTS_A, averagePoints)));
        return switch ((int) rounded) {
            case 4 -> "A";
            case 3 -> "B";
            case 2 -> "C";
            default -> "D";
        };
    }

    /**
     * One day's meal-quality aggregate: how many meals were logged, how many of those
     * carry a usable grade, and what those grades average to.
     *
     * <p>{@code gradedMeals} is reported alongside {@code mealsLogged} on purpose —
     * manually logged meals never get a grade, and averaging over the graded subset
     * while showing the full count is the only honest way to say "3 meals, 2 assessed".
     * A day with no graded meals gets a null average rather than a zero, so the client
     * can say "not assessed" instead of "poor".
     */
    public static Map<String, Object> dayAggregate(Collection<MealEntry> meals) {
        int mealsLogged = 0;
        int gradedMeals = 0;
        int totalPoints = 0;

        for (MealEntry entry : meals == null ? List.<MealEntry>of() : meals) {
            if (entry == null) {
                continue;
            }
            mealsLogged++;
            Integer points = pointsOf(entry);
            if (points != null) {
                gradedMeals++;
                totalPoints += points;
            }
        }

        Double averagePoints = gradedMeals > 0
                ? Math.round((double) totalPoints / gradedMeals * 100.0) / 100.0
                : null;

        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("mealsLogged", mealsLogged);
        aggregate.put("gradedMeals", gradedMeals);
        aggregate.put("averagePoints", averagePoints);
        aggregate.put("letter", letterFor(averagePoints));
        return aggregate;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
