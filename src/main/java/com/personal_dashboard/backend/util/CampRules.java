package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.dto.CampView.ChestItem;
import com.personal_dashboard.backend.dto.CampView.Quest;
import com.personal_dashboard.backend.dto.CampView.Season;
import com.personal_dashboard.backend.dto.CampView.SeasonWeek;
import com.personal_dashboard.backend.dto.CampView.Sticker;
import com.personal_dashboard.backend.dto.GoalBoardResponse.GoalProgressView;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCheckIn;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The camp's economy and quests, in one pure place — no clock, no repository — so the rules
 * are testable alone and guest mode ports them line for line (mocks/guest-goals.ts).
 *
 * <p>The guardrails (design/GOALS_BUDDY_PLAN.md, Phase 3):
 * <ul>
 *   <li>Sparks only come in: the chest pays for kept weeks (plus a sticker bonus), quests
 *       pay when claimed. Nothing decays, nothing is taken away.</li>
 *   <li>No chance anywhere. The chest holds exactly what was earned; quests are picked from
 *       the date and the goal list, so the same day always asks the same things.</li>
 *   <li>A quest that isn't done simply isn't done — there is no failed state.</li>
 * </ul>
 */
public final class CampRules {

    public static final int SPARKS_PER_KEPT_WEEK = 10;

    /** Stickers by lifetime kept weeks per goal, with the chest's bonus for each. */
    public static final List<Sticker> STICKER_TIERS = List.of(
            Sticker.builder().weeks(1).name("First week").bonus(5).build(),
            Sticker.builder().weeks(4).name("A month strong").bonus(15).build(),
            Sticker.builder().weeks(12).name("A whole season").bonus(40).build(),
            Sticker.builder().weeks(26).name("Half a year").bonus(80).build(),
            Sticker.builder().weeks(52).name("A full year").bonus(150).build());

    public static final String SLOT_HAT = "hat";
    public static final String SLOT_NECK = "neck";
    public static final String SLOT_FACE = "face";
    public static final String SLOT_DECOR = "decor";
    public static final Set<String> WEAR_SLOTS = Set.of(SLOT_HAT, SLOT_NECK, SLOT_FACE);

    public record CampItem(String id, String slot, int price) {
    }

    /** Fen's cart. Fixed prices, nothing time-limited. Mirrored in features/goals/camp-catalog.ts. */
    public static final List<CampItem> ITEMS = List.of(
            new CampItem("acorn-cap", SLOT_HAT, 30),
            new CampItem("party-hat", SLOT_HAT, 40),
            new CampItem("beanie", SLOT_HAT, 55),
            new CampItem("straw-hat", SLOT_HAT, 70),
            new CampItem("flower-crown", SLOT_HAT, 90),
            new CampItem("wizard-hat", SLOT_HAT, 160),
            new CampItem("bucket-hat", SLOT_HAT, 65),
            new CampItem("headphones", SLOT_HAT, 85),
            new CampItem("crown", SLOT_HAT, 260),
            new CampItem("night-cap", SLOT_HAT, 75),
            new CampItem("keeper-cap", SLOT_HAT, 140),
            new CampItem("bow-tie", SLOT_NECK, 35),
            new CampItem("bandana", SLOT_NECK, 45),
            new CampItem("scarf", SLOT_NECK, 50),
            new CampItem("flower-lei", SLOT_NECK, 60),
            new CampItem("medal", SLOT_NECK, 110),
            new CampItem("star-scarf", SLOT_NECK, 70),
            new CampItem("round-glasses", SLOT_FACE, 60),
            new CampItem("star-shades", SLOT_FACE, 90),
            new CampItem("heart-shades", SLOT_FACE, 80),
            new CampItem("moon-monocle", SLOT_FACE, 95),
            new CampItem("flower-bed", SLOT_DECOR, 50),
            new CampItem("bunting", SLOT_DECOR, 60),
            new CampItem("mushroom-lamps", SLOT_DECOR, 80),
            new CampItem("fairy-lights", SLOT_DECOR, 100),
            new CampItem("guitar", SLOT_DECOR, 120),
            new CampItem("telescope", SLOT_DECOR, 180),
            new CampItem("pinwheel", SLOT_DECOR, 45),
            new CampItem("pumpkins", SLOT_DECOR, 55),
            new CampItem("signpost", SLOT_DECOR, 70),
            new CampItem("birdhouse", SLOT_DECOR, 75),
            new CampItem("lamp-post", SLOT_DECOR, 90),
            new CampItem("picnic", SLOT_DECOR, 95),
            new CampItem("pond", SLOT_DECOR, 140),
            new CampItem("cherry-tree", SLOT_DECOR, 220));

    public static final String QUEST_LIGHT_N = "light-n";
    public static final String QUEST_LIGHT_GOAL = "light-goal";
    public static final String QUEST_NOTE = "note";
    public static final String QUEST_EARLY = "early";
    public static final String QUEST_AMOUNT = "amount";
    public static final String QUEST_ALL = "all";

    /** Claimed quest keys older than this many days are pruned — they can't be claimed again anyway. */
    public static final int QUEST_KEEP_DAYS = 14;

    private CampRules() {
    }

    public static Optional<CampItem> item(String id) {
        return ITEMS.stream().filter(i -> i.id().equals(id)).findFirst();
    }

    // ── The chest ────────────────────────────────────────────────────────

    /**
     * What the chest holds: for each goal, its kept weeks not yet paid out, at
     * {@value #SPARKS_PER_KEPT_WEEK} sparks each, plus the bonus for any sticker those weeks
     * earned. Driven by lifetime {@code weeksKept}, which only grows.
     */
    public static List<ChestItem> chest(List<GoalProgressView> views, Map<String, Integer> paid) {
        List<ChestItem> out = new ArrayList<>();
        for (GoalProgressView v : views) {
            Goal g = v.getGoal();
            int already = paid == null ? 0 : paid.getOrDefault(g.getId(), 0);
            int kept = v.getWeeksKept();
            if (kept <= already) {
                continue;
            }
            List<Sticker> stickers = STICKER_TIERS.stream()
                    .filter(t -> t.getWeeks() > already && t.getWeeks() <= kept)
                    .toList();
            int sparks = (kept - already) * SPARKS_PER_KEPT_WEEK + stickers.stream().mapToInt(Sticker::getBonus).sum();
            out.add(ChestItem.builder()
                    .goalId(g.getId())
                    .title(g.getTitle())
                    .icon(g.getIcon())
                    .color(g.getColor())
                    .weeks(kept - already)
                    .sparks(sparks)
                    .stickers(stickers)
                    .build());
        }
        return out;
    }

    // ── Quests ───────────────────────────────────────────────────────────

    /**
     * The day's three quests from Wren, judged on that day's check-ins. The pick depends only
     * on the date and the goals that existed that day, so it never changes as you log.
     * Slot 0 lights lanterns, slot 1 is a small flourish, slot 2 is the day's bonus.
     */
    public static List<Quest> quests(LocalDate day, List<Goal> goals, Map<String, List<GoalCheckIn>> byGoal,
                                     Set<String> claimed, ZoneId zone) {
        List<Goal> present = goals.stream()
                .filter(g -> g.getStartDate() == null || !g.getStartDate().isAfter(day))
                .sorted(Comparator.comparingInt(Goal::getOrder).thenComparing(Goal::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        if (present.isEmpty()) {
            return List.of();
        }
        String d = day.toString();
        List<Goal> counts = present.stream().filter(Goal::isCount).toList();

        List<String> kinds = new ArrayList<>(3);
        kinds.add(present.size() >= 2 ? QUEST_LIGHT_N : QUEST_LIGHT_GOAL);
        List<String> flourish = new ArrayList<>(List.of(QUEST_NOTE, QUEST_EARLY));
        if (!counts.isEmpty()) {
            flourish.add(QUEST_AMOUNT);
        }
        if (present.size() >= 2) {
            flourish.add(QUEST_LIGHT_GOAL);
        }
        String second = flourish.get(Math.floorMod((d + "#2").hashCode(), flourish.size()));
        kinds.add(second);
        if (present.size() >= 2) {
            kinds.add(QUEST_ALL);
        } else {
            List<String> rest = new ArrayList<>(flourish);
            rest.remove(second);
            kinds.add(rest.get(Math.floorMod((d + "#3").hashCode(), rest.size())));
        }

        Goal named = present.get(Math.floorMod((d + "#goal").hashCode(), present.size()));
        Goal amountGoal = counts.isEmpty() ? null : counts.get(Math.floorMod((d + "#amount").hashCode(), counts.size()));

        List<Quest> out = new ArrayList<>(3);
        for (int slot = 0; slot < kinds.size(); slot++) {
            String id = d + ":" + slot;
            Quest q = judge(kinds.get(slot), day, present, byGoal, named, amountGoal, zone);
            q.setId(id);
            q.setDate(d);
            q.setClaimed(claimed != null && claimed.contains(id));
            out.add(q);
        }
        return out;
    }

    private static Quest judge(String kind, LocalDate day, List<Goal> goals, Map<String, List<GoalCheckIn>> byGoal,
                               Goal named, Goal amountGoal, ZoneId zone) {
        List<GoalCheckIn> dayCheckIns = goals.stream()
                .flatMap(g -> byGoal.getOrDefault(g.getId(), List.of()).stream())
                .filter(c -> day.equals(c.getDate()))
                .toList();
        Quest.QuestBuilder q = Quest.builder().kind(kind);
        switch (kind) {
            case QUEST_LIGHT_N -> {
                int n = goals.size() >= 4 ? 3 : 2;
                long lit = goals.stream().filter(g -> hitOn(g, byGoal, day)).count();
                q.target(n).progress(Math.min(n, lit)).reward(n >= 3 ? 6 : 4);
            }
            case QUEST_LIGHT_GOAL -> q.goalId(named.getId()).goalTitle(named.getTitle())
                    .target(1).progress(hitOn(named, byGoal, day) ? 1 : 0).reward(4);
            case QUEST_NOTE -> q.target(1)
                    .progress(dayCheckIns.stream().anyMatch(c -> c.getNote() != null && !c.getNote().isBlank()) ? 1 : 0)
                    .reward(3);
            case QUEST_EARLY -> q.target(1)
                    .progress(dayCheckIns.stream().anyMatch(c -> loggedBeforeNoon(c, day, zone)) ? 1 : 0)
                    .reward(4);
            case QUEST_AMOUNT -> {
                double amount = amountGoal.isDayPeriod() ? amountGoal.getTarget() : Math.ceil(amountGoal.getTarget() / 7.0);
                double logged = byGoal.getOrDefault(amountGoal.getId(), List.of()).stream()
                        .filter(c -> day.equals(c.getDate()))
                        .mapToDouble(GoalCheckIn::getValue)
                        .sum();
                q.goalId(amountGoal.getId()).goalTitle(amountGoal.getTitle()).amount(amount).unit(amountGoal.getUnit())
                        .target(amount).progress(Math.min(amount, logged)).reward(4);
            }
            case QUEST_ALL -> {
                long done = goals.stream().filter(g -> {
                    GoalProgressView v = GoalProgress.evaluate(g, byGoal.getOrDefault(g.getId(), List.of()), day);
                    return v.getToday().isHit() || v.getWeek().isKept();
                }).count();
                q.target(goals.size()).progress(done).reward(8);
            }
            default -> throw new IllegalStateException("Unknown quest kind " + kind);
        }
        Quest built = q.build();
        built.setDone(built.getProgress() + 1e-9 >= built.getTarget());
        return built;
    }

    private static boolean hitOn(Goal g, Map<String, List<GoalCheckIn>> byGoal, LocalDate day) {
        double sum = byGoal.getOrDefault(g.getId(), List.of()).stream()
                .filter(c -> day.equals(c.getDate()))
                .mapToDouble(GoalCheckIn::getValue)
                .sum();
        return GoalProgress.isDayHit(g, sum);
    }

    private static boolean loggedBeforeNoon(GoalCheckIn c, LocalDate day, ZoneId zone) {
        if (c.getCreatedAt() == null) {
            return false;
        }
        var at = c.getCreatedAt().atZone(zone);
        return at.toLocalDate().equals(day) && at.getHour() < 12;
    }

    // ── The season map ───────────────────────────────────────────────────

    /**
     * The season {@code today} falls in (meteorological: winter Dec–Feb, spring Mar–May,
     * summer Jun–Aug, autumn Sep–Nov), week by week: how many goals existed and how many
     * were kept. Weeks ahead are drawn but never judged.
     */
    public static Season season(List<Goal> goals, Map<String, List<GoalCheckIn>> byGoal, LocalDate today) {
        int m = today.getMonthValue();
        String name;
        LocalDate start;
        if (m == 12 || m <= 2) {
            name = "winter";
            start = LocalDate.of(m == 12 ? today.getYear() : today.getYear() - 1, Month.DECEMBER, 1);
        } else if (m <= 5) {
            name = "spring";
            start = LocalDate.of(today.getYear(), Month.MARCH, 1);
        } else if (m <= 8) {
            name = "summer";
            start = LocalDate.of(today.getYear(), Month.JUNE, 1);
        } else {
            name = "autumn";
            start = LocalDate.of(today.getYear(), Month.SEPTEMBER, 1);
        }
        LocalDate end = start.plusMonths(3).minusDays(1);

        LocalDate thisWeek = GoalProgress.weekStart(today);
        List<Set<LocalDate>> kept = goals.stream()
                .map(g -> GoalProgress.keptWeeks(g, byGoal.getOrDefault(g.getId(), List.of()), today))
                .toList();

        List<SeasonWeek> weeks = new ArrayList<>();
        for (LocalDate ws = GoalProgress.weekStart(start); !ws.isAfter(end); ws = ws.plusWeeks(1)) {
            int present = 0;
            int keptCount = 0;
            for (int i = 0; i < goals.size(); i++) {
                LocalDate goalWeek = GoalProgress.weekStart(GoalProgress.startOf(goals.get(i), today));
                if (!goalWeek.isAfter(ws)) {
                    present++;
                    if (kept.get(i).contains(ws)) {
                        keptCount++;
                    }
                }
            }
            weeks.add(SeasonWeek.builder()
                    .weekStart(ws.toString())
                    .goals(present)
                    .kept(keptCount)
                    .current(ws.equals(thisWeek))
                    .future(ws.isAfter(thisWeek))
                    .build());
        }
        return Season.builder()
                .key(name + "-" + (name.equals("winter") && m <= 2 ? today.getYear() - 1 : today.getYear()))
                .name(name)
                .start(start.toString())
                .end(end.toString())
                .weeks(weeks)
                .build();
    }
}
