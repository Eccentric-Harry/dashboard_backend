package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.GoalKitPageRequest;
import com.personal_dashboard.backend.model.GoalKit;
import com.personal_dashboard.backend.repository.GoalKitRepository;
import com.personal_dashboard.backend.repository.GoalRepository;
import com.personal_dashboard.backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A goal world's kit (model/GoalKit): read with the goal's journey, written one page at a
 * time. Each write is a single upsert that sets only that page, so two pages saved from
 * two tabs never overwrite each other, and the first write creates the document.
 */
@Service
@RequiredArgsConstructor
public class GoalKitService {

    /** Far more pages than any world has; it only stops the map growing without end. */
    static final int MAX_PAGES = 24;

    private static final Pattern PAGE_KEY = Pattern.compile("^[a-z-]{1,24}$");

    private final GoalKitRepository kitRepository;
    private final GoalRepository goalRepository;
    private final MongoTemplate mongoTemplate;

    /** Every page of the goal's kit; empty when nothing has been packed yet. */
    public Map<String, GoalKit.Page> pages(String userId, String goalId) {
        return kitRepository.findByUserIdAndGoalId(userId, goalId)
                .map(GoalKit::getPages)
                .filter(Objects::nonNull)
                .orElse(Map.of());
    }

    /** Saves one page (blank picks and fields are dropped) and returns the whole kit. */
    public Map<String, GoalKit.Page> savePage(String goalId, String page, GoalKitPageRequest request) {
        String userId = UserContext.getRequiredUserId();
        if (page == null || !PAGE_KEY.matcher(page).matches()) {
            throw new IllegalArgumentException("Unknown kit page: " + page);
        }
        goalRepository.findByIdAndUserId(goalId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Goal not found with id: " + goalId));

        Map<String, GoalKit.Page> before = pages(userId, goalId);
        if (!before.containsKey(page) && before.size() >= MAX_PAGES) {
            throw new IllegalArgumentException("This kit is full.");
        }

        Instant now = Instant.now();
        List<String> picks = request.getPicks() == null ? List.of() : request.getPicks().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        Map<String, String> fields = new LinkedHashMap<>();
        if (request.getFields() != null) {
            request.getFields().forEach((key, value) -> {
                if (value != null && !value.isBlank()) fields.put(key, value.trim());
            });
        }

        Query mine = new Query(Criteria.where("userId").is(userId).and("goalId").is(goalId));
        Update update = new Update()
                .set("pages." + page, GoalKit.Page.builder().picks(picks).fields(fields).updatedAt(now).build())
                .set("updatedAt", now)
                .setOnInsert("userId", userId)
                .setOnInsert("goalId", goalId);
        mongoTemplate.upsert(mine, update, GoalKit.class);
        return pages(userId, goalId);
    }

    /** With the goal: a deleted goal takes its kit with it. */
    public void deleteFor(String userId, String goalId) {
        kitRepository.deleteByUserIdAndGoalId(userId, goalId);
    }
}
