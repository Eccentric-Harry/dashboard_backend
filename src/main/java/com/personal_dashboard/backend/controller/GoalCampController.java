package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.CampView;
import com.personal_dashboard.backend.dto.request.CampBuyRequest;
import com.personal_dashboard.backend.dto.request.CampClaimRequest;
import com.personal_dashboard.backend.dto.request.CampFirstLightRequest;
import com.personal_dashboard.backend.dto.request.CampLookRequest;
import com.personal_dashboard.backend.service.GoalCampService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The camp around the /goals board. Like the goals endpoints, each takes an optional
 * {@code today} (the client's local day, within a day of the server's).
 */
@RestController
@RequestMapping("/api/v1/goals/camp")
@RequiredArgsConstructor
@Tag(name = "Goals camp", description = "Sparks, the camp chest, daily quests, the shop and the buddy's look")
public class GoalCampController {

    private final GoalCampService campService;

    @GetMapping
    @Operation(summary = "The camp", description = "Sparks, chest contents, today's quests, the season map and the buddy's look")
    public ResponseEntity<ApiResponse<CampView>> getCamp(@RequestParam(name = "today", required = false) String today) {
        return ok(campService.getCamp(parse(today)), "camp");
    }

    @PostMapping("/chest/open")
    @Operation(summary = "Open the camp chest", description = "Pays out every kept week not yet paid, sticker bonuses included")
    public ResponseEntity<ApiResponse<CampView.ChestOpenResult>> openChest(@RequestParam(name = "today", required = false) String today) {
        return ok(campService.openChest(parse(today)), "chest-open");
    }

    @PostMapping("/quests/claim")
    @Operation(summary = "Claim a quest", description = "Today's or yesterday's; claiming twice pays once")
    public ResponseEntity<ApiResponse<CampView.QuestClaimResult>> claimQuest(
            @Valid @RequestBody CampClaimRequest request,
            @RequestParam(name = "today", required = false) String today) {
        return ok(campService.claimQuest(request.getQuestId(), parse(today)), "quest-claim");
    }

    @PostMapping("/shop/buy")
    @Operation(summary = "Buy from Fen's cart", description = "Spends sparks and puts the item on (or out)")
    public ResponseEntity<ApiResponse<CampView>> buy(
            @Valid @RequestBody CampBuyRequest request,
            @RequestParam(name = "today", required = false) String today) {
        return ok(campService.buy(request.getItemId(), parse(today)), "shop-buy");
    }

    @PutMapping("/look")
    @Operation(summary = "Name and dress the buddy", description = "Replaces the name, worn items and decorations on show")
    public ResponseEntity<ApiResponse<CampView>> setLook(
            @Valid @RequestBody CampLookRequest request,
            @RequestParam(name = "today", required = false) String today) {
        return ok(campService.setLook(request, parse(today)), "look");
    }

    @PutMapping("/first-light")
    @Operation(summary = "Hoot's first light", description = "The lantern to light first today or tomorrow; a null goalId clears it")
    public ResponseEntity<ApiResponse<CampView>> setFirstLight(
            @Valid @RequestBody CampFirstLightRequest request,
            @RequestParam(name = "today", required = false) String today) {
        return ok(campService.setFirstLight(request, parse(today)), "first-light");
    }

    private static LocalDate parse(String date) {
        return date == null || date.isBlank() ? null : LocalDate.parse(date);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data, String action) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .data(data)
                .meta(ApiMeta.builder()
                        .requestId(UUID.randomUUID().toString())
                        .timestamp(Instant.now().toString())
                        .source("goals-" + action)
                        .build())
                .build());
    }
}
