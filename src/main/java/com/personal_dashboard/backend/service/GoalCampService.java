package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.CampView;
import com.personal_dashboard.backend.dto.CampView.ChestItem;
import com.personal_dashboard.backend.dto.CampView.Quest;
import com.personal_dashboard.backend.dto.GoalBoardResponse.GoalProgressView;
import com.personal_dashboard.backend.dto.request.CampLookRequest;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCamp;
import com.personal_dashboard.backend.model.GoalCheckIn;
import com.personal_dashboard.backend.repository.GoalCampRepository;
import com.personal_dashboard.backend.repository.GoalCheckInRepository;
import com.personal_dashboard.backend.repository.GoalRepository;
import com.personal_dashboard.backend.security.UserContext;
import com.personal_dashboard.backend.util.CampRules;
import com.personal_dashboard.backend.util.CampRules.CampItem;
import com.personal_dashboard.backend.util.GoalProgress;
import com.personal_dashboard.backend.util.ParallelReads;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The camp around the goals board: the chest, Wren's quests, Fen's cart and the buddy's
 * look. Reads are derived by {@link CampRules}; writes touch only the user's
 * {@link GoalCamp} document, each as one conditional atomic update — the condition is what
 * makes a double tap (or two tabs) unable to pay a chest twice, claim a quest twice or buy
 * with sparks that aren't there.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoalCampService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final GoalRepository goalRepository;
    private final GoalCheckInRepository checkInRepository;
    private final GoalCampRepository campRepository;
    private final MongoTemplate mongoTemplate;

    // ── Reads ────────────────────────────────────────────────────────────

    public CampView getCamp(LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        return read(userId, resolveToday(requestedToday));
    }

    /** The camp for a board that has already read its goals and check-ins (GoalService.getBoard). */
    public CampView build(GoalCamp camp, List<Goal> goals, Map<String, List<GoalCheckIn>> byGoal,
                          List<GoalProgressView> views, LocalDate today) {
        GoalCamp c = camp != null ? camp : GoalCamp.builder().build();
        Set<String> claimed = c.getQuestsClaimed() == null ? Set.of() : new HashSet<>(c.getQuestsClaimed());
        List<Quest> yesterday = CampRules.quests(today.minusDays(1), goals, byGoal, claimed, ZONE).stream()
                .filter(q -> q.isDone() && !q.isClaimed())
                .toList();
        Set<String> active = views.stream().map(v -> v.getGoal().getId()).collect(Collectors.toSet());
        int grown = views.stream().mapToInt(GoalProgressView::getWeeksKept).sum()
                + (c.getChestPaid() == null ? 0 : c.getChestPaid().entrySet().stream()
                        .filter(e -> !active.contains(e.getKey()))
                        .mapToInt(e -> e.getValue() == null ? 0 : e.getValue())
                        .sum());
        return CampView.builder()
                .grownWeeks(grown)
                .buddyName(c.getBuddyName())
                .sparks(c.getSparks())
                .sparksEarned(c.getSparksEarned())
                .owned(c.getOwned() == null ? List.of() : c.getOwned())
                .equipped(c.getEquipped() == null ? Map.of() : c.getEquipped())
                .decor(c.getDecor() == null ? List.of() : c.getDecor())
                .decorAt(c.getDecorAt() == null ? Map.of() : c.getDecorAt())
                .chest(CampRules.chest(views, c.getChestPaid()))
                .quests(CampRules.quests(today, goals, byGoal, claimed, ZONE))
                .questsYesterday(yesterday)
                .season(CampRules.season(goals, byGoal, today))
                .build();
    }

    public GoalCamp readCampDocument(String userId) {
        return campRepository.findByUserId(userId).orElse(null);
    }

    // ── The chest ────────────────────────────────────────────────────────

    /** Opens the chest: everything inside is paid out at once and the reveal shows it. */
    public CampView.ChestOpenResult openChest(LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = resolveToday(requestedToday);
        Snapshot s = snapshot(userId, today);
        GoalCamp camp = ensureCamp(userId);
        List<ChestItem> items = CampRules.chest(s.views(), camp.getChestPaid());
        if (items.isEmpty()) {
            return CampView.ChestOpenResult.builder().opened(List.of()).sparks(0).camp(build(camp, s, today)).build();
        }

        // Pay out only if nothing has been paid since we read the watermarks.
        Criteria where = Criteria.where("userId").is(userId);
        Update update = new Update().set("updatedAt", Instant.now());
        int total = 0;
        Map<String, Integer> paid = camp.getChestPaid() == null ? Map.of() : camp.getChestPaid();
        Map<String, Integer> keptById = s.views().stream()
                .collect(Collectors.toMap(v -> v.getGoal().getId(), GoalProgressView::getWeeksKept));
        for (ChestItem item : items) {
            String path = "chestPaid." + item.getGoalId();
            Integer before = paid.get(item.getGoalId());
            where = where.and(path).is(before);
            update.set(path, keptById.get(item.getGoalId()));
            total += item.getSparks();
        }
        update.inc("sparks", total).inc("sparksEarned", total);
        UpdateResult result = mongoTemplate.updateFirst(new Query(where), update, GoalCamp.class);
        GoalCamp after = campRepository.findByUserId(userId).orElse(camp);
        if (result.getModifiedCount() == 0) {
            log.info("Camp chest for {} was already opened elsewhere", userId);
            return CampView.ChestOpenResult.builder().opened(List.of()).sparks(0).camp(build(after, s, today)).build();
        }
        log.info("Camp chest opened: {} sparks across {} goals", total, items.size());
        return CampView.ChestOpenResult.builder().opened(items).sparks(total).camp(build(after, s, today)).build();
    }

    // ── Quests ───────────────────────────────────────────────────────────

    /** Claims a done quest from today or yesterday. Claiming twice is a no-op, not an error. */
    public CampView.QuestClaimResult claimQuest(String questId, LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = resolveToday(requestedToday);
        LocalDate day = LocalDate.parse(questId.substring(0, 10));
        if (!day.equals(today) && !day.equals(today.minusDays(1))) {
            throw new IllegalArgumentException("That letter has been put away — today's quests are fresh ones.");
        }
        Snapshot s = snapshot(userId, today);
        GoalCamp camp = ensureCamp(userId);
        Quest quest = CampRules.quests(day, s.goals(), s.byGoal(), Set.of(), ZONE).stream()
                .filter(q -> q.getId().equals(questId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Quest not found: " + questId));
        if (!quest.isDone()) {
            throw new IllegalArgumentException("Not done yet — it'll be ready to claim once it is.");
        }

        Query unclaimed = new Query(Criteria.where("userId").is(userId).and("questsClaimed").ne(questId));
        Update pay = new Update()
                .push("questsClaimed", questId)
                .inc("sparks", quest.getReward())
                .inc("sparksEarned", quest.getReward())
                .set("updatedAt", Instant.now());
        boolean paid = mongoTemplate.updateFirst(unclaimed, pay, GoalCamp.class).getModifiedCount() > 0;
        if (paid) {
            String cutoff = today.minusDays(CampRules.QUEST_KEEP_DAYS).toString();
            mongoTemplate.updateFirst(new Query(Criteria.where("userId").is(userId)),
                    new Update().pull("questsClaimed", new Document("$lt", cutoff)), GoalCamp.class);
        }
        GoalCamp after = campRepository.findByUserId(userId).orElse(camp);
        return CampView.QuestClaimResult.builder()
                .reward(paid ? quest.getReward() : 0)
                .camp(build(after, s, today))
                .build();
    }

    // ── Fen's cart ───────────────────────────────────────────────────────

    /** Buys an item and puts it straight on (or out, for decorations). */
    public CampView buy(String itemId, LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = resolveToday(requestedToday);
        CampItem item = CampRules.item(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Fen doesn't sell that."));
        ensureCamp(userId);

        Query affordable = new Query(Criteria.where("userId").is(userId)
                .and("owned").ne(itemId)
                .and("sparks").gte(item.price()));
        Update update = new Update()
                .push("owned", itemId)
                .inc("sparks", -item.price())
                .set("updatedAt", Instant.now());
        if (CampRules.WEAR_SLOTS.contains(item.slot())) {
            update.set("equipped." + item.slot(), itemId);
        } else {
            update.push("decor", itemId);
        }
        if (mongoTemplate.updateFirst(affordable, update, GoalCamp.class).getModifiedCount() == 0) {
            GoalCamp camp = campRepository.findByUserId(userId).orElseThrow();
            if (camp.getOwned() != null && camp.getOwned().contains(itemId)) {
                throw new IllegalArgumentException("That's already yours.");
            }
            throw new IllegalArgumentException("Not enough sparks for that yet.");
        }
        log.info("Camp purchase: {} for {} sparks", itemId, item.price());
        return read(userId, today);
    }

    /** Names the buddy and sets what it wears, which decorations are out and where they stand. */
    public CampView setLook(CampLookRequest request, LocalDate requestedToday) {
        String userId = UserContext.getRequiredUserId();
        LocalDate today = resolveToday(requestedToday);
        GoalCamp camp = ensureCamp(userId);
        Set<String> owned = camp.getOwned() == null ? Set.of() : new HashSet<>(camp.getOwned());

        Map<String, String> equipped = new LinkedHashMap<>();
        if (request.getEquipped() != null) {
            request.getEquipped().forEach((slot, id) -> {
                if (id == null || id.isBlank()) {
                    return;
                }
                CampItem item = CampRules.item(id)
                        .filter(i -> i.slot().equals(slot) && CampRules.WEAR_SLOTS.contains(slot))
                        .orElseThrow(() -> new IllegalArgumentException("That doesn't go there."));
                if (!owned.contains(item.id())) {
                    throw new IllegalArgumentException("Buy it from Fen first.");
                }
                equipped.put(slot, item.id());
            });
        }
        List<String> decor = new ArrayList<>(new LinkedHashSet<>(request.getDecor() == null ? List.of() : request.getDecor()));
        for (String id : decor) {
            boolean ok = CampRules.item(id).filter(i -> CampRules.SLOT_DECOR.equals(i.slot())).isPresent() && owned.contains(id);
            if (!ok) {
                throw new IllegalArgumentException("Buy it from Fen first.");
            }
        }
        // Spots: the request's if it sent any, else the ones already saved — kept only for
        // decorations still out, so a piece put away and brought back returns to its default.
        Map<String, GoalCamp.DecorSpot> decorAt = new LinkedHashMap<>();
        if (request.getDecorAt() != null) {
            request.getDecorAt().forEach((id, spot) -> {
                if (decor.contains(id) && spot != null) {
                    decorAt.put(id, new GoalCamp.DecorSpot(spot.getX(), spot.getY()));
                }
            });
        } else if (camp.getDecorAt() != null) {
            camp.getDecorAt().forEach((id, spot) -> {
                if (decor.contains(id)) {
                    decorAt.put(id, spot);
                }
            });
        }
        String name = request.getBuddyName() == null || request.getBuddyName().isBlank()
                ? null
                : request.getBuddyName().trim().replaceAll("\\s+", " ");

        mongoTemplate.updateFirst(new Query(Criteria.where("userId").is(userId)),
                new Update().set("equipped", equipped).set("decor", decor).set("decorAt", decorAt).set("buddyName", name).set("updatedAt", Instant.now()),
                GoalCamp.class);
        return read(userId, today);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private record Snapshot(List<Goal> goals, Map<String, List<GoalCheckIn>> byGoal, List<GoalProgressView> views) {
    }

    private Snapshot snapshot(String userId, LocalDate today) {
        CompletableFuture<List<Goal>> goalsRead = ParallelReads.fork(
                () -> goalRepository.findByUserIdAndStatusOrderByOrderAsc(userId, Goal.STATUS_ACTIVE));
        CompletableFuture<List<GoalCheckIn>> checkInsRead = ParallelReads.fork(
                () -> checkInRepository.findByUserId(userId));
        List<Goal> goals = ParallelReads.join(goalsRead);
        Map<String, List<GoalCheckIn>> byGoal = ParallelReads.join(checkInsRead).stream()
                .collect(Collectors.groupingBy(GoalCheckIn::getGoalId));
        List<GoalProgressView> views = goals.stream()
                .map(g -> GoalProgress.evaluate(g, byGoal.getOrDefault(g.getId(), List.of()), today))
                .toList();
        return new Snapshot(goals, byGoal, views);
    }

    private CampView build(GoalCamp camp, Snapshot s, LocalDate today) {
        return build(camp, s.goals(), s.byGoal(), s.views(), today);
    }

    private CampView read(String userId, LocalDate today) {
        CompletableFuture<GoalCamp> campRead = ParallelReads.fork(() -> readCampDocument(userId));
        Snapshot s = snapshot(userId, today);
        return build(ParallelReads.join(campRead), s, today);
    }

    /**
     * The user's camp document, created on first write. Created by an upsert on userId alone
     * (never on a condition, which could insert a duplicate when the condition fails); the
     * unique userId index settles two first writes racing.
     */
    GoalCamp ensureCamp(String userId) {
        return campRepository.findByUserId(userId).orElseGet(() -> {
            try {
                mongoTemplate.upsert(new Query(Criteria.where("userId").is(userId)),
                        new Update().setOnInsert("sparks", 0).setOnInsert("sparksEarned", 0)
                                .setOnInsert("createdAt", Instant.now()),
                        GoalCamp.class);
            } catch (DuplicateKeyException e) {
                // Another request created it a moment ago — read that one.
            }
            return campRepository.findByUserId(userId).orElseThrow();
        });
    }

    /**
     * The client's local day (it knows about the 04:00 rollover) — but only within a day of
     * the server's, so a hand-edited {@code today} can't claim quests from other dates.
     */
    private LocalDate resolveToday(LocalDate requested) {
        LocalDate server = serverToday();
        if (requested == null || Math.abs(java.time.temporal.ChronoUnit.DAYS.between(server, requested)) > 1) {
            return server;
        }
        return requested;
    }

    LocalDate serverToday() {
        return LocalDate.now(ZONE);
    }
}
