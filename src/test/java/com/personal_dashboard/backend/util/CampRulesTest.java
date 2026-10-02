package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.dto.CampView.ChestItem;
import com.personal_dashboard.backend.dto.CampView.Quest;
import com.personal_dashboard.backend.dto.CampView.Season;
import com.personal_dashboard.backend.dto.GoalBoardResponse.GoalProgressView;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCheckIn;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CampRulesTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    // Monday 28 Sep 2026 → Sunday 4 Oct 2026.
    private static final LocalDate MON = LocalDate.of(2026, 9, 28);
    private static final LocalDate THU = MON.plusDays(3);

    private static Goal goal(String id, int order, String measure, String period, double target) {
        return Goal.builder().id(id).userId("u").title("Goal " + id).order(order).measure(measure).period(period)
                .target(target).unit(Goal.MEASURE_COUNT.equals(measure) ? "pages" : null)
                .daysPerWeek(Goal.PERIOD_DAY.equals(period) ? 1 : null)
                .startDate(MON.minusWeeks(6)).status(Goal.STATUS_ACTIVE).build();
    }

    private static GoalCheckIn at(String goalId, LocalDate date, double value, String note, int hour) {
        return GoalCheckIn.builder().goalId(goalId).userId("u").date(date).value(value).note(note)
                .createdAt(ZonedDateTime.of(date.atTime(hour, 0), ZONE).toInstant()).build();
    }

    private static List<Goal> board() {
        return List.of(
                goal("a", 0, Goal.MEASURE_COUNT, Goal.PERIOD_DAY, 10),
                goal("b", 1, Goal.MEASURE_CHECK, Goal.PERIOD_WEEK, 3),
                goal("c", 2, Goal.MEASURE_CHECK, Goal.PERIOD_DAY, 1));
    }

    private static Map<String, List<GoalCheckIn>> group(List<GoalCheckIn> checkIns) {
        Map<String, List<GoalCheckIn>> out = new HashMap<>();
        checkIns.forEach(c -> out.computeIfAbsent(c.getGoalId(), k -> new ArrayList<>()).add(c));
        return out;
    }

    @Test
    void chestPaysTenAWeekPlusStickerBonusesAndRespectsWhatWasPaid() {
        Goal g = goal("a", 0, Goal.MEASURE_CHECK, Goal.PERIOD_DAY, 1);
        GoalProgressView v = GoalProgressView.builder().goal(g).weeksKept(5).build();

        List<ChestItem> fresh = CampRules.chest(List.of(v), Map.of());
        assertEquals(1, fresh.size());
        assertEquals(5, fresh.get(0).getWeeks());
        // 5 weeks × 10, plus "First week" (5) and "A month strong" (15).
        assertEquals(70, fresh.get(0).getSparks());
        assertEquals(List.of(1, 4), fresh.get(0).getStickers().stream().map(s -> s.getWeeks()).toList());

        List<ChestItem> rest = CampRules.chest(List.of(v), Map.of("a", 4));
        assertEquals(1, rest.get(0).getWeeks());
        assertEquals(10, rest.get(0).getSparks());
        assertTrue(rest.get(0).getStickers().isEmpty());

        assertTrue(CampRules.chest(List.of(v), Map.of("a", 5)).isEmpty(), "nothing left once paid");
        // A rule edit that lowers the count never takes anything back.
        assertTrue(CampRules.chest(List.of(v), Map.of("a", 7)).isEmpty());
    }

    @Test
    void questsArePickedFromTheDateAloneAndDontChangeAsYouLog() {
        List<Goal> goals = board();
        List<Quest> before = CampRules.quests(THU, goals, Map.of(), Set.of(), ZONE);
        List<Quest> after = CampRules.quests(THU, goals,
                group(List.of(at("a", THU, 10, "chapter 3", 9), at("c", THU, 1, null, 20))), Set.of(), ZONE);

        assertEquals(3, before.size());
        assertEquals(before.stream().map(Quest::getKind).toList(), after.stream().map(Quest::getKind).toList());
        assertEquals(List.of(THU + ":0", THU + ":1", THU + ":2"), before.stream().map(Quest::getId).toList());
        assertEquals(CampRules.QUEST_LIGHT_N, before.get(0).getKind());
        assertEquals(CampRules.QUEST_ALL, before.get(2).getKind());
        assertTrue(before.stream().noneMatch(Quest::isDone), "nothing is done before anything is logged");
    }

    @Test
    void questsAreJudgedOnThatDaysCheckIns() {
        List<Goal> goals = board();
        Map<String, List<GoalCheckIn>> byGoal = group(List.of(
                at("a", THU, 10, "chapter 3", 9),
                at("b", THU, 1, null, 13),
                at("c", THU, 1, null, 21),
                // Yesterday's note doesn't count today.
                at("a", THU.minusDays(1), 4, "old note", 8)));

        List<Quest> quests = CampRules.quests(THU, goals, byGoal, Set.of(THU + ":0"), ZONE);
        Quest lightN = quests.get(0);
        assertEquals(2, lightN.getTarget());
        assertTrue(lightN.isDone());
        assertTrue(lightN.isClaimed());
        assertTrue(quests.get(2).isDone(), "every lantern lit");
        assertEquals(3, quests.get(2).getProgress());

        // The flourish quests judge the same day, whichever one the date picked.
        for (String kind : List.of(CampRules.QUEST_NOTE, CampRules.QUEST_EARLY)) {
            LocalDate day = findDayWithSecond(goals, kind);
            List<Quest> none = CampRules.quests(day, goals, Map.of(), Set.of(), ZONE);
            assertFalse(none.get(1).isDone());
            Map<String, List<GoalCheckIn>> late = group(List.of(at("a", day, 2, null, 18)));
            assertFalse(CampRules.quests(day, goals, late, Set.of(), ZONE).get(1).isDone(), kind + " needs its own thing");
            Map<String, List<GoalCheckIn>> good = group(List.of(at("a", day, 2, "a note", 8)));
            assertTrue(CampRules.quests(day, goals, good, Set.of(), ZONE).get(1).isDone(), kind);
        }
    }

    @Test
    void anAmountQuestAsksForTheDaysShare() {
        List<Goal> goals = board();
        LocalDate day = findDayWithSecond(goals, CampRules.QUEST_AMOUNT);
        Quest q = CampRules.quests(day, goals, group(List.of(at("a", day, 6, null, 10))), Set.of(), ZONE).get(1);
        assertEquals("a", q.getGoalId());
        assertEquals(10, q.getAmount());
        assertEquals(6, q.getProgress());
        assertFalse(q.isDone());
    }

    @Test
    void aOneGoalCampGetsThreeQuestsWithoutTheAllQuest() {
        List<Goal> one = List.of(goal("a", 0, Goal.MEASURE_COUNT, Goal.PERIOD_DAY, 10));
        for (int i = 0; i < 14; i++) {
            List<Quest> q = CampRules.quests(MON.plusDays(i), one, Map.of(), Set.of(), ZONE);
            assertEquals(3, q.size());
            assertEquals(CampRules.QUEST_LIGHT_GOAL, q.get(0).getKind());
            assertNotEquals(q.get(1).getKind(), q.get(2).getKind());
            assertTrue(q.stream().noneMatch(x -> CampRules.QUEST_ALL.equals(x.getKind())));
        }
        assertTrue(CampRules.quests(THU, List.of(), Map.of(), Set.of(), ZONE).isEmpty());
        // A goal that starts tomorrow isn't part of today.
        Goal later = one.get(0).toBuilder().startDate(THU.plusDays(1)).build();
        assertTrue(CampRules.quests(THU, List.of(later), Map.of(), Set.of(), ZONE).isEmpty());
    }

    @Test
    void theSeasonMapCountsKeptWeeksAndNeverJudgesTheFuture() {
        Goal g = goal("c", 0, Goal.MEASURE_CHECK, Goal.PERIOD_DAY, 1).toBuilder().startDate(LocalDate.of(2026, 9, 14)).build();
        // Kept the week of 14 Sep and this week (1 of 7 days each).
        Map<String, List<GoalCheckIn>> byGoal = group(List.of(
                at("c", LocalDate.of(2026, 9, 15), 1, null, 9),
                at("c", THU, 1, null, 9)));

        Season s = CampRules.season(List.of(g), byGoal, THU);
        assertEquals("autumn", s.getName());
        assertEquals("autumn-2026", s.getKey());
        assertEquals("2026-08-31", s.getWeeks().get(0).getWeekStart());
        assertEquals("2026-11-30", s.getWeeks().get(s.getWeeks().size() - 1).getWeekStart());

        var byWeek = new HashMap<String, com.personal_dashboard.backend.dto.CampView.SeasonWeek>();
        s.getWeeks().forEach(w -> byWeek.put(w.getWeekStart(), w));
        assertEquals(0, byWeek.get("2026-09-07").getGoals(), "before the goal existed");
        assertEquals(1, byWeek.get("2026-09-14").getKept());
        assertEquals(0, byWeek.get("2026-09-21").getKept());
        assertTrue(byWeek.get("2026-09-28").isCurrent());
        assertEquals(1, byWeek.get("2026-09-28").getKept());
        assertTrue(byWeek.get("2026-10-05").isFuture());
        assertEquals(0, byWeek.get("2026-10-05").getKept());

        assertEquals("winter-2026", CampRules.season(List.of(), Map.of(), LocalDate.of(2027, 1, 10)).getKey());
    }

    @Test
    void keptWeeksAgreesWithTheBoardsLifetimeCount() {
        Goal g = goal("a", 0, Goal.MEASURE_COUNT, Goal.PERIOD_DAY, 10);
        List<GoalCheckIn> checkIns = List.of(
                at("a", MON.minusWeeks(3), 10, null, 9),
                at("a", MON.minusWeeks(1).plusDays(2), 12, null, 9),
                at("a", MON.plusDays(1), 10, null, 9));
        assertEquals(GoalProgress.evaluate(g, checkIns, THU).getWeeksKept(), GoalProgress.keptWeeks(g, checkIns, THU).size());
        assertEquals(3, GoalProgress.keptWeeks(g, checkIns, THU).size());
    }

    @Test
    void everyShopItemHasAKnownSlotAndAFairPrice() {
        assertEquals(CampRules.ITEMS.size(), CampRules.ITEMS.stream().map(CampRules.CampItem::id).distinct().count());
        for (CampRules.CampItem item : CampRules.ITEMS) {
            assertTrue(CampRules.WEAR_SLOTS.contains(item.slot()) || CampRules.SLOT_DECOR.equals(item.slot()), item.id());
            assertTrue(item.price() > 0 && item.price() <= 300, item.id());
        }
        assertTrue(CampRules.item("crown").isPresent());
        assertTrue(CampRules.item("lootbox").isEmpty());
    }

    /** A day in the coming weeks whose second quest is {@code kind} (the pick is date-driven). */
    private static LocalDate findDayWithSecond(List<Goal> goals, String kind) {
        for (int i = 0; i < 60; i++) {
            LocalDate d = MON.plusDays(i);
            if (kind.equals(CampRules.quests(d, goals, Map.of(), Set.of(), ZONE).get(1).getKind())) {
                return d;
            }
        }
        throw new AssertionError("No day in 60 picked " + kind);
    }
}
