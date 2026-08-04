package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.model.MealEntry;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MealGradesTest {

    @Test
    void readsLetterGradesIncludingSuffixedOnes() {
        assertEquals(Integer.valueOf(4), MealGrades.pointsOf("A"));
        assertEquals(Integer.valueOf(4), MealGrades.pointsOf("a+"));
        assertEquals(Integer.valueOf(3), MealGrades.pointsOf("B-"));
        assertEquals(Integer.valueOf(2), MealGrades.pointsOf("C"));
        assertEquals(Integer.valueOf(1), MealGrades.pointsOf("D"));
        assertEquals(Integer.valueOf(4), MealGrades.pointsOf("A grade"));
    }

    @Test
    void collapsesGradesBelowDIntoTheLowestTier() {
        assertEquals(Integer.valueOf(1), MealGrades.pointsOf("E"));
        assertEquals(Integer.valueOf(1), MealGrades.pointsOf("F"));
    }

    @Test
    void readsWordGradingsFromOlderRecords() {
        assertEquals(Integer.valueOf(4), MealGrades.pointsOf("Excellent"));
        assertEquals(Integer.valueOf(3), MealGrades.pointsOf("good"));
        assertEquals(Integer.valueOf(2), MealGrades.pointsOf("fair"));
        assertEquals(Integer.valueOf(1), MealGrades.pointsOf("poor"));
    }

    /** Words starting a–f must not be mistaken for letter grades. */
    @Test
    void wordsBeginningWithGradeLettersStayWords() {
        assertEquals(Integer.valueOf(2), MealGrades.pointsOf("average"));
        assertEquals(Integer.valueOf(3), MealGrades.pointsOf("balanced"));
        assertEquals(Integer.valueOf(1), MealGrades.pointsOf("bad"));
        assertEquals(Integer.valueOf(2), MealGrades.pointsOf("fair"));
    }

    @Test
    void returnsNullWhenThereIsNoUsableGrading() {
        assertNull(MealGrades.pointsOf((String) null));
        assertNull(MealGrades.pointsOf("   "));
        assertNull(MealGrades.pointsOf("delicious"));
        assertNull(MealGrades.pointsOf((MealEntry) null));
        assertNull(MealGrades.pointsOf(MealEntry.builder().description("Manual entry").build()));
    }

    @Test
    void prefersTheAiLetterGradeOverTheTopLevelField() {
        MealEntry entry = MealEntry.builder()
                .mealQuality("poor")
                .recompositionAssessment(Map.of("letter_grade", "A", "meal_quality", "fair"))
                .build();
        assertEquals(Integer.valueOf(4), MealGrades.pointsOf(entry));
    }

    @Test
    void fallsBackThroughTheAssessmentWordThenTheTopLevelField() {
        MealEntry wordOnly = MealEntry.builder()
                .mealQuality("poor")
                .recompositionAssessment(Map.of("meal_quality", "excellent"))
                .build();
        assertEquals(Integer.valueOf(4), MealGrades.pointsOf(wordOnly));

        MealEntry topLevelOnly = MealEntry.builder()
                .mealQuality("fair")
                .recompositionAssessment(Map.of("notes", "no grade here"))
                .build();
        assertEquals(Integer.valueOf(2), MealGrades.pointsOf(topLevelOnly));
    }

    @Test
    void aggregatesADaysMealsIntoCountsAndAnAverage() {
        Map<String, Object> day = MealGrades.dayAggregate(List.of(
                MealEntry.builder().mealQuality("A").build(),
                MealEntry.builder().mealQuality("B").build(),
                MealEntry.builder().mealQuality("C").build()));

        assertEquals(3, day.get("mealsLogged"));
        assertEquals(3, day.get("gradedMeals"));
        assertEquals(3.0, day.get("averagePoints"));
        assertEquals("B", day.get("letter"));
    }

    /** Manual entries count as meals eaten, but must not drag the quality average. */
    @Test
    void ungradedMealsCountTowardTheDayWithoutEnteringTheAverage() {
        Map<String, Object> day = MealGrades.dayAggregate(List.of(
                MealEntry.builder().mealQuality("A").build(),
                MealEntry.builder().description("Manual entry").build()));

        assertEquals(2, day.get("mealsLogged"));
        assertEquals(1, day.get("gradedMeals"));
        assertEquals(4.0, day.get("averagePoints"));
        assertEquals("A", day.get("letter"));
    }

    @Test
    void reportsNoAverageWhenNothingWasGraded() {
        for (Collection<MealEntry> meals : List.of(
                List.<MealEntry>of(),
                List.of(MealEntry.builder().description("Manual entry").build()))) {
            Map<String, Object> day = MealGrades.dayAggregate(meals);
            assertEquals(0, day.get("gradedMeals"));
            assertNull(day.get("averagePoints"));
            assertNull(day.get("letter"));
        }
        assertEquals(0, MealGrades.dayAggregate(null).get("mealsLogged"));
    }

    @Test
    void roundsAveragedPointsBackToALetter() {
        assertEquals("A", MealGrades.letterFor(3.6));
        assertEquals("B", MealGrades.letterFor(3.0));
        assertEquals("B", MealGrades.letterFor(2.5));
        assertEquals("C", MealGrades.letterFor(2.4));
        assertEquals("D", MealGrades.letterFor(1.2));
        assertNull(MealGrades.letterFor(null));
    }
}
