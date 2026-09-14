package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.AddPursuitStepRequest;
import com.personal_dashboard.backend.dto.request.PursuitRequest;
import com.personal_dashboard.backend.dto.request.PursuitStepInput;
import com.personal_dashboard.backend.dto.request.UpdatePursuitStepRequest;
import com.personal_dashboard.backend.model.Learning;
import com.personal_dashboard.backend.model.LearningPursuit;
import com.personal_dashboard.backend.model.LearningPursuit.PursuitStep;
import com.personal_dashboard.backend.repository.LearningPursuitRepository;
import com.personal_dashboard.backend.repository.LearningRepository;
import com.personal_dashboard.backend.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Nested pursuit steps: parent completion is derived from children, ticking a parent
 * sets its subtree, and finishing the last leaf migrates the pursuit to the learnings log.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LearningPursuitServiceTest {

    private static final String USER = "test-user";

    @Mock private LearningPursuitRepository repository;
    @Mock private LearningRepository learningRepository;
    @Mock private NotionIntegrationService notionService;

    @InjectMocks private LearningPursuitService service;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER);
        when(repository.save(any(LearningPursuit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(notionService.createNotionPage(anyString())).thenReturn("https://notion.so/x");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static PursuitStepInput input(String text, PursuitStepInput... children) {
        PursuitStepInput in = new PursuitStepInput(text);
        in.setChildren(List.of(children));
        return in;
    }

    private static PursuitStep step(String id, boolean done, PursuitStep... children) {
        return PursuitStep.builder().id(id).text(id).isCompleted(done)
                .children(new ArrayList<>(List.of(children))).build();
    }

    private LearningPursuit stored(PursuitStep... steps) {
        LearningPursuit pursuit = LearningPursuit.builder()
                .id("p1").userId(USER).title("JS").category("Development")
                .steps(new ArrayList<>(List.of(steps))).build();
        when(repository.findByIdAndUserId("p1", USER)).thenReturn(Optional.of(pursuit));
        return pursuit;
    }

    @Test
    void createBuildsNestedTreeAndSkipsBlankSteps() {
        PursuitRequest request = new PursuitRequest();
        request.setTitle(" JS ");
        request.setCategory("Development");
        request.setSteps(List.of(
                input("Functions", input("Arrow functions", input("Implicit return")), input("  ")),
                input("")));

        LearningPursuit created = service.createPursuit(request);

        assertEquals("JS", created.getTitle());
        assertEquals(1, created.getSteps().size());
        PursuitStep functions = created.getSteps().get(0);
        assertEquals(1, functions.getChildren().size());
        assertEquals("Implicit return", functions.getChildren().get(0).getChildren().get(0).getText());
        assertNotNull(functions.getChildren().get(0).getId());
    }

    @Test
    void createRejectsFourthLevel() {
        PursuitRequest request = new PursuitRequest();
        request.setTitle("JS");
        request.setCategory("Development");
        request.setSteps(List.of(input("a", input("b", input("c", input("d"))))));

        assertThrows(IllegalArgumentException.class, () -> service.createPursuit(request));
        verify(repository, never()).save(any());
    }

    @Test
    void togglingParentTicksWholeSubtree() {
        LearningPursuit pursuit = stored(
                step("parent", false, step("a", true), step("b", false, step("b1", false))),
                step("other", false));

        LearningPursuit result = service.toggleStep("p1", "parent");

        PursuitStep parent = result.getSteps().get(0);
        assertTrue(parent.isCompleted());
        assertTrue(parent.getChildren().get(1).getChildren().get(0).isCompleted());
        assertEquals("ACTIVE", result.getStatus());
        verify(learningRepository, never()).save(any());
        assertSame(pursuit, result);
    }

    @Test
    void completingLastLeafDerivesParentsAndMigratesPursuit() {
        stored(step("parent", false, step("a", true), step("b", false)));

        LearningPursuit result = service.toggleStep("p1", "b");

        assertTrue(result.getSteps().get(0).isCompleted());
        assertEquals("COMPLETED", result.getStatus());
        ArgumentCaptor<Learning> learning = ArgumentCaptor.forClass(Learning.class);
        verify(learningRepository).save(learning.capture());
        assertTrue(learning.getValue().getDescription().contains("  - b"));
        verify(repository).delete(result);
    }

    @Test
    void addingSubStepReopensCompletedParent() {
        stored(step("parent", true, step("a", true)), step("other", false));

        LearningPursuit result = service.addStep("p1", addRequest("parent", "new"));

        PursuitStep parent = result.getSteps().get(0);
        assertFalse(parent.isCompleted());
        assertEquals("new", parent.getChildren().get(1).getText());
    }

    @Test
    void addingBelowThirdLevelIsRejected() {
        stored(step("a", false, step("b", false, step("c", false))));

        assertThrows(IllegalArgumentException.class, () -> service.addStep("p1", addRequest("c", "d")));
    }

    @Test
    void deletingParentRemovesSubtreeAndHandlesLegacyStepsWithoutChildren() {
        PursuitStep legacy = PursuitStep.builder().id("legacy").text("legacy").isCompleted(false).children(null).build();
        stored(step("parent", false, step("a", false)), legacy);

        LearningPursuit result = service.deleteStep("p1", "a");

        assertEquals(2, result.getSteps().size());
        assertTrue(result.getSteps().get(0).getChildren().isEmpty());
        assertNotNull(result.getSteps().get(1).getChildren());
    }

    @Test
    void createClampsLeafEstimatesDropsParentEstimatesAndMakesFirstPursuitPrimary() {
        PursuitStepInput parent = input("Parent", input("Leaf"));
        parent.setEstimateMinutes(90);
        parent.getChildren().get(0).setEstimateMinutes(900);
        PursuitRequest request = new PursuitRequest();
        request.setTitle("JS");
        request.setCategory("Development");
        request.setGoal("  Read library source  ");
        request.setSteps(List.of(parent));

        LearningPursuit created = service.createPursuit(request);

        assertNull(created.getSteps().get(0).getEstimateMinutes());
        assertEquals(600, created.getSteps().get(0).getChildren().get(0).getEstimateMinutes());
        assertEquals("Read library source", created.getGoal());
        assertTrue(created.isPrimary());
    }

    @Test
    void createDoesNotStealPrimaryFromExistingPursuit() {
        LearningPursuit existing = LearningPursuit.builder().id("p0").userId(USER).primary(true).build();
        when(repository.findByUserId(USER)).thenReturn(List.of(existing));
        PursuitRequest request = new PursuitRequest();
        request.setTitle("JS");
        request.setCategory("Development");

        assertFalse(service.createPursuit(request).isPrimary());
    }

    @Test
    void togglingStampsAndClearsCompletedAt() {
        stored(step("a", false), step("b", false));

        PursuitStep a = service.toggleStep("p1", "a").getSteps().get(0);
        assertNotNull(a.getCompletedAt());

        assertNull(service.toggleStep("p1", "a").getSteps().get(0).getCompletedAt());
    }

    @Test
    void updateStepAppliesOnlyProvidedFields() {
        PursuitStep leaf = step("a", false);
        leaf.setEstimateMinutes(45);
        leaf.setResumeNote("old note");
        stored(leaf);

        UpdatePursuitStepRequest request = new UpdatePursuitStepRequest();
        request.setResumeNote("Stopped at selectors");
        request.setTakeaways("- Sets dedupe listeners");
        PursuitStep updated = service.updateStep("p1", "a", request).getSteps().get(0);

        assertEquals("a", updated.getText());
        assertEquals(45, updated.getEstimateMinutes());
        assertEquals("Stopped at selectors", updated.getResumeNote());
        assertEquals("- Sets dedupe listeners", updated.getTakeaways());

        UpdatePursuitStepRequest clear = new UpdatePursuitStepRequest();
        clear.setEstimateMinutes(0);
        clear.setResumeNote(" ");
        PursuitStep cleared = service.updateStep("p1", "a", clear).getSteps().get(0);
        assertNull(cleared.getEstimateMinutes());
        assertNull(cleared.getResumeNote());
    }

    @Test
    void setPrimaryIsExclusive() {
        LearningPursuit target = stored(step("a", false));
        LearningPursuit other = LearningPursuit.builder().id("p2").userId(USER).primary(true).build();
        target.setPrimary(false);
        when(repository.findByUserId(USER)).thenReturn(List.of(target, other));

        LearningPursuit result = service.setPrimary("p1");

        assertTrue(result.isPrimary());
        assertFalse(other.isPrimary());
        verify(repository).saveAll(any());
    }

    @Test
    void finishingPrimaryPursuitPromotesOldestRemaining() {
        LearningPursuit pursuit = stored(step("a", false));
        pursuit.setPrimary(true);
        LearningPursuit newer = LearningPursuit.builder().id("p3").userId(USER).createdAt(Instant.parse("2026-09-10T00:00:00Z")).build();
        LearningPursuit older = LearningPursuit.builder().id("p2").userId(USER).createdAt(Instant.parse("2026-09-01T00:00:00Z")).build();
        when(repository.findByUserId(USER)).thenReturn(List.of(newer, older));

        service.toggleStep("p1", "a");

        assertTrue(older.isPrimary());
        assertFalse(newer.isPrimary());
    }

    @Test
    void creditStepTimeAddsMinutesAndIgnoresMissingStep() {
        PursuitStep leaf = step("a", false);
        leaf.setSpentMinutes(20);
        stored(leaf);

        service.creditStepTime(USER, "p1", "a", 25);
        service.creditStepTime(USER, "p1", "gone", 25);

        assertEquals(45, leaf.getSpentMinutes());
        verify(repository, times(1)).save(any(LearningPursuit.class));
    }

    @Test
    void learningDescriptionCarriesTakeaways() {
        PursuitStep leaf = step("a", false);
        leaf.setTakeaways("Closures capture bindings\nNot values");
        stored(leaf);

        service.toggleStep("p1", "a");

        ArgumentCaptor<Learning> learning = ArgumentCaptor.forClass(Learning.class);
        verify(learningRepository).save(learning.capture());
        assertTrue(learning.getValue().getDescription().contains("    > Closures capture bindings"));
        assertTrue(learning.getValue().getDescription().contains("    > Not values"));
    }

    private static AddPursuitStepRequest addRequest(String parentId, String text) {
        AddPursuitStepRequest request = new AddPursuitStepRequest();
        request.setParentId(parentId);
        request.setText(text);
        return request;
    }
}
