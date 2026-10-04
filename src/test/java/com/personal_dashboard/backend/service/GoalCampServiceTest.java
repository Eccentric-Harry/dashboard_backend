package com.personal_dashboard.backend.service;

import com.mongodb.client.result.UpdateResult;
import com.personal_dashboard.backend.dto.CampView;
import com.personal_dashboard.backend.dto.request.CampLookRequest;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalCamp;
import com.personal_dashboard.backend.model.GoalCheckIn;
import com.personal_dashboard.backend.repository.GoalCampRepository;
import com.personal_dashboard.backend.repository.GoalCheckInRepository;
import com.personal_dashboard.backend.repository.GoalRepository;
import com.personal_dashboard.backend.security.UserContext;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoalCampServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private GoalCheckInRepository checkInRepository;

    @Mock
    private GoalCampRepository campRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    @Spy
    @InjectMocks
    private GoalCampService service;

    private GoalCamp camp;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u");
        doReturn(TODAY).when(service).serverToday();
        camp = GoalCamp.builder().id("c").userId("u").sparks(40).owned(List.of("acorn-cap")).build();
        when(campRepository.findByUserId("u")).thenAnswer(inv -> Optional.of(camp));
        Goal goal = Goal.builder().id("g").userId("u").title("Ten quiet minutes").measure(Goal.MEASURE_CHECK)
                .period(Goal.PERIOD_DAY).target(1).daysPerWeek(4).status(Goal.STATUS_ACTIVE)
                .startDate(TODAY.minusWeeks(3)).build();
        when(goalRepository.findByUserIdAndStatusOrderByOrderAsc("u", Goal.STATUS_ACTIVE)).thenReturn(List.of(goal));
        when(checkInRepository.findByUserId("u")).thenReturn(List.of(
                GoalCheckIn.builder().id("ci").goalId("g").userId("u").date(TODAY).value(1).build()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void updateMatches(long modified) {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(GoalCamp.class)))
                .thenReturn(UpdateResult.acknowledged(modified, modified, null));
    }

    @Test
    void aClaimPaysOnceAndASecondClaimIsAQuietNoOp() {
        updateMatches(1);
        // Today's first quest (one goal → "light it") is done by the check-in above.
        CampView.QuestClaimResult first = service.claimQuest(TODAY + ":0", TODAY);
        assertEquals(4, first.getReward());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(query.capture(), any(Update.class), eq(GoalCamp.class));
        assertTrue(query.getAllValues().get(0).getQueryObject().toJson().contains("\"$ne\": \"" + TODAY + ":0\""),
                "the payout is conditional on the quest not being claimed yet");

        updateMatches(0);
        CampView.QuestClaimResult again = service.claimQuest(TODAY + ":0", TODAY);
        assertEquals(0, again.getReward());
    }

    @Test
    void oldLettersAndUnfinishedQuestsCantBeClaimed() {
        assertThrows(IllegalArgumentException.class, () -> service.claimQuest(TODAY.minusDays(2) + ":0", TODAY));
        // Yesterday had no check-in, so its "light it" quest isn't done.
        assertThrows(IllegalArgumentException.class, () -> service.claimQuest(TODAY.minusDays(1) + ":0", TODAY));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(GoalCamp.class));
    }

    @Test
    void buyingNeedsTheSparksAndIsConditionalOnThem() {
        assertThrows(IllegalArgumentException.class, () -> service.buy("lootbox", TODAY));

        updateMatches(0);
        IllegalArgumentException poor = assertThrows(IllegalArgumentException.class, () -> service.buy("crown", TODAY));
        assertTrue(poor.getMessage().contains("Not enough sparks"));
        IllegalArgumentException owned = assertThrows(IllegalArgumentException.class, () -> service.buy("acorn-cap", TODAY));
        assertTrue(owned.getMessage().contains("already yours"));

        updateMatches(1);
        service.buy("scarf", TODAY);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(query.capture(), update.capture(), eq(GoalCamp.class));
        String q = query.getValue().getQueryObject().toJson();
        assertTrue(q.contains("\"sparks\": {\"$gte\": 50}"), q);
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertEquals("scarf", set.get("equipped.neck"), "bought clothes go straight on");
    }

    @Test
    void theLookOnlyTakesOwnedThingsInTheirOwnSlots() {
        assertThrows(IllegalArgumentException.class, () -> service.setLook(
                CampLookRequest.builder().equipped(Map.of("hat", "crown")).build(), TODAY));
        assertThrows(IllegalArgumentException.class, () -> service.setLook(
                CampLookRequest.builder().equipped(Map.of("neck", "acorn-cap")).build(), TODAY));
        assertThrows(IllegalArgumentException.class, () -> service.setLook(
                CampLookRequest.builder().decor(List.of("telescope")).build(), TODAY));

        updateMatches(1);
        service.setLook(CampLookRequest.builder().buddyName("  Sprout   Jr ").equipped(Map.of("hat", "acorn-cap")).build(), TODAY);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(GoalCamp.class));
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertEquals("Sprout Jr", set.get("buddyName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void decorationsKeepTheirSpotsOnlyWhileTheyreOut() {
        camp.setOwned(List.of("pond", "telescope"));
        camp.setDecor(List.of("pond", "telescope"));
        camp.setDecorAt(Map.of("pond", new GoalCamp.DecorSpot(0.2, 0.3), "telescope", new GoalCamp.DecorSpot(0.9, 0.1)));
        updateMatches(1);

        // No spots sent: the saved ones stay, minus the telescope that was put away.
        service.setLook(CampLookRequest.builder().decor(List.of("pond")).build(), TODAY);
        // Spots sent: they replace the old ones, for decorations that are out.
        service.setLook(CampLookRequest.builder().decor(List.of("pond", "telescope"))
                .decorAt(Map.of("telescope", new CampLookRequest.Spot(0.5, 0.5), "crown", new CampLookRequest.Spot(0.1, 0.1))).build(), TODAY);

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(Query.class), update.capture(), eq(GoalCamp.class));
        Map<String, GoalCamp.DecorSpot> kept = (Map<String, GoalCamp.DecorSpot>) ((Document) update.getAllValues().get(0).getUpdateObject().get("$set")).get("decorAt");
        assertEquals(Map.of("pond", new GoalCamp.DecorSpot(0.2, 0.3)), kept);
        Map<String, GoalCamp.DecorSpot> moved = (Map<String, GoalCamp.DecorSpot>) ((Document) update.getAllValues().get(1).getUpdateObject().get("$set")).get("decorAt");
        assertEquals(Map.of("telescope", new GoalCamp.DecorSpot(0.5, 0.5)), moved);
    }

    @Test
    void theChestPaysOnlyIfNothingWasPaidSinceItWasRead() {
        // A once-a-week daily goal, so today's check-in keeps this week.
        Goal easy = Goal.builder().id("g").userId("u").title("Ten quiet minutes").measure(Goal.MEASURE_CHECK)
                .period(Goal.PERIOD_DAY).target(1).daysPerWeek(1).status(Goal.STATUS_ACTIVE)
                .startDate(TODAY.minusWeeks(3)).build();
        when(goalRepository.findByUserIdAndStatusOrderByOrderAsc("u", Goal.STATUS_ACTIVE)).thenReturn(List.of(easy));

        updateMatches(1);
        CampView.ChestOpenResult opened = service.openChest(TODAY);
        assertEquals(1, opened.getOpened().size());
        assertEquals(15, opened.getSparks(), "one kept week plus the first-week sticker");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(query.capture(), any(Update.class), eq(GoalCamp.class));
        assertTrue(query.getValue().getQueryObject().toJson().contains("\"chestPaid.g\": null"));

        updateMatches(0);
        assertTrue(service.openChest(TODAY).getOpened().isEmpty(), "another tab got there first");
    }

    @Test
    void aHandEditedTodayFallsBackToTheServersDay() {
        updateMatches(1);
        // A claim "for" next month is judged against the server's today, which has no such quest.
        assertThrows(IllegalArgumentException.class,
                () -> service.claimQuest("2026-11-01:0", LocalDate.of(2026, 11, 1)));
    }
}
