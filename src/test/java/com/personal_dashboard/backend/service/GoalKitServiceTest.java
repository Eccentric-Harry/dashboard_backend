package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.GoalKitPageRequest;
import com.personal_dashboard.backend.model.Goal;
import com.personal_dashboard.backend.model.GoalKit;
import com.personal_dashboard.backend.repository.GoalKitRepository;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoalKitServiceTest {

    @Mock
    private GoalKitRepository kitRepository;

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private GoalKitService service;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void ownsGoal() {
        when(goalRepository.findByIdAndUserId("g", "u")).thenReturn(Optional.of(Goal.builder().id("g").userId("u").build()));
    }

    @Test
    void savesOnePageWithASingleUpsertScopedToTheUserAndGoal() {
        ownsGoal();
        when(kitRepository.findByUserIdAndGoalId("u", "g")).thenReturn(Optional.empty());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("own", "  my jaw goes tight  ");
        fields.put("blank", "   ");
        service.savePage("g", "signs", GoalKitPageRequest.builder()
                .picks(Arrays.asList(" Tight shoulders ", "Tight shoulders", "", "Racing thoughts"))
                .fields(fields)
                .build());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(query.capture(), update.capture(), eq(GoalKit.class));
        Document where = query.getValue().getQueryObject();
        assertEquals("u", where.get("userId"));
        assertEquals("g", where.get("goalId"));

        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        GoalKit.Page page = (GoalKit.Page) set.get("pages.signs");
        assertEquals(List.of("Tight shoulders", "Racing thoughts"), page.getPicks());
        assertEquals(Map.of("own", "my jaw goes tight"), page.getFields());
        assertFalse(set.containsKey("pages.breath"), "only the saved page is written");
        Document onInsert = (Document) update.getValue().getUpdateObject().get("$setOnInsert");
        assertEquals("u", onInsert.get("userId"));
    }

    @Test
    void refusesAGoalThatIsNotTheUsers() {
        when(goalRepository.findByIdAndUserId("g", "u")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.savePage("g", "signs", GoalKitPageRequest.builder().build()));
        verify(mongoTemplate, never()).upsert(any(), any(), eq(GoalKit.class));
    }

    @Test
    void refusesAPageKeyThatCouldReachIntoOtherFields() {
        for (String bad : List.of("signs.picks", "$set", "Signs", "", "a".repeat(25))) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.savePage("g", bad, GoalKitPageRequest.builder().build()), bad);
        }
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void aFullKitTakesNoNewPagesButStillUpdatesItsOwn() {
        ownsGoal();
        Map<String, GoalKit.Page> full = new HashMap<>();
        IntStream.range(0, GoalKitService.MAX_PAGES).forEach(i -> full.put("p" + (char) ('a' + i), GoalKit.Page.builder().build()));
        when(kitRepository.findByUserIdAndGoalId("u", "g")).thenReturn(Optional.of(GoalKit.builder().pages(full).build()));
        assertThrows(IllegalArgumentException.class, () -> service.savePage("g", "new-page", GoalKitPageRequest.builder().build()));
        assertDoesNotThrow(() -> service.savePage("g", "pa", GoalKitPageRequest.builder().build()));
    }

    @Test
    void anEmptyKitReadsAsNoPages() {
        when(kitRepository.findByUserIdAndGoalId("u", "g")).thenReturn(Optional.empty());
        assertTrue(service.pages("u", "g").isEmpty());
    }
}
