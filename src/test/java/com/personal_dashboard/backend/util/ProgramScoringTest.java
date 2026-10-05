package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.model.Program;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ProgramScoringTest {

    @Test
    void rosenbergReversesItemsTwoFiveSixEightAndNine() {
        // Strongly agree with every positive statement, strongly disagree with every negative one.
        List<Integer> best = List.of(3, 0, 3, 3, 0, 0, 3, 0, 0, 3);
        assertEquals(30, ProgramScoring.rosenberg(best));
        List<Integer> worst = List.of(0, 3, 0, 0, 3, 3, 0, 3, 3, 0);
        assertEquals(0, ProgramScoring.rosenberg(worst));
        // "Agree" to everything: 5 positives × 2 + 5 negatives × (3 − 2).
        assertEquals(15, ProgramScoring.rosenberg(List.of(2, 2, 2, 2, 2, 2, 2, 2, 2, 2)));
    }

    @Test
    void rosenbergRejectsTheWrongShape() {
        assertThrows(IllegalArgumentException.class, () -> ProgramScoring.rosenberg(List.of(1, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> ProgramScoring.rosenberg(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 4)));
        assertThrows(IllegalArgumentException.class, () -> ProgramScoring.rosenberg(null));
    }

    @Test
    void who5IsTheRawSumTimesFour() {
        assertEquals(100, ProgramScoring.who5(List.of(5, 5, 5, 5, 5)));
        assertEquals(0, ProgramScoring.who5(List.of(0, 0, 0, 0, 0)));
        assertEquals(52, ProgramScoring.who5(List.of(3, 3, 2, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> ProgramScoring.who5(List.of(6, 0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> ProgramScoring.who5(List.of(1, 1, 1, 1)));
    }

    @Test
    void defaultTracksFollowTheBrief() {
        Map<String, Program.Track> byKey = ProgramScoring.defaultTracks(70.0).stream()
                .collect(Collectors.toMap(Program.Track::getKey, t -> t));
        assertEquals(ProgramScoring.TRACKS, ProgramScoring.defaultTracks(70.0).stream().map(Program.Track::getKey).toList());
        assertEquals(3.0, byKey.get("run").getTarget());
        assertEquals(3.0, byKey.get("lift").getTarget());
        assertEquals(112.0, byKey.get("protein").getTarget()); // 1.6 g/kg
        assertEquals(84.0, byKey.get("protein").getFloor());   // 1.2 g/kg
        assertNull(byKey.get("mood").getTarget(), "mood is observed, never targeted");
        assertFalse(byKey.containsKey("screen"), "the screen track was retired");
        assertEquals(5.0, byKey.get("learn").getTarget());
        assertEquals(15.0, byKey.get("english").getTarget());
        assertEquals(5.0, byKey.get("english").getFloor());
        assertEquals(1.0, byKey.get("regard").getTarget());

        Map<String, Program.Track> unknown = ProgramScoring.defaultTracks(null).stream()
                .collect(Collectors.toMap(Program.Track::getKey, t -> t));
        assertEquals(ProgramScoring.DEFAULT_PROTEIN_G, unknown.get("protein").getTarget());
        assertEquals(ProgramScoring.DEFAULT_PROTEIN_FLOOR_G, unknown.get("protein").getFloor());
    }
}
