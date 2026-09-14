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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Pursuits hold a tree of steps: step → sub-step → sub-sub-step (MAX_DEPTH levels).
 * Only leaves are ticked directly; a parent's isCompleted is derived from its children
 * and re-derived after every mutation. Ticking a parent sets its whole subtree. When
 * every leaf is complete the pursuit is migrated into the learnings log and deleted.
 *
 * Each user has at most one primary ("main") pursuit — the one the Next-up panel works
 * from. The first pursuit becomes primary, and when the primary pursuit is completed or
 * deleted the oldest remaining one takes over.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningPursuitService {

    /** Levels of nesting allowed, counting top-level steps as level 1. */
    public static final int MAX_DEPTH = 3;
    /** Upper bound on steps across the whole tree — guards against runaway AI output. */
    public static final int MAX_TOTAL_STEPS = 150;
    private static final int MAX_TEXT_LENGTH = 200;
    private static final int MAX_NOTE_LENGTH = 300;
    private static final int MIN_ESTIMATE = 5;
    private static final int MAX_ESTIMATE = 600;

    private final LearningPursuitRepository repository;
    private final LearningRepository learningRepository;
    private final NotionIntegrationService notionService;

    public List<LearningPursuit> getAllPursuits() {
        log.info("Retrieving all learning pursuits");
        String userId = UserContext.getRequiredUserId();
        return repository.findByUserId(userId);
    }

    public LearningPursuit createPursuit(PursuitRequest request) {
        log.info("Creating pursuit model for: {}", request.getTitle());
        String userId = UserContext.getRequiredUserId();

        List<PursuitStep> steps = buildSteps(request.getSteps(), 1);
        if (countSteps(steps) > MAX_TOTAL_STEPS) {
            throw new IllegalArgumentException("A pursuit can have at most " + MAX_TOTAL_STEPS + " steps");
        }

        boolean hasPrimary = repository.findByUserId(userId).stream().anyMatch(LearningPursuit::isPrimary);

        // Create the Notion page dynamically
        String notionUrl = notionService.createNotionPage(request.getTitle());

        LearningPursuit pursuit = LearningPursuit.builder()
                .title(request.getTitle().trim())
                .category(request.getCategory().trim())
                .goal(blankToNull(request.getGoal()))
                .notionUrl(notionUrl)
                .status("ACTIVE")
                .primary(!hasPrimary)
                .steps(steps)
                .build();

        return repository.save(pursuit);
    }

    public LearningPursuit toggleStep(String pursuitId, String stepId) {
        log.info("Toggling step {} within pursuit {}", stepId, pursuitId);
        LearningPursuit pursuit = findOwned(pursuitId);

        PursuitStep step = findStep(pursuit.getSteps(), stepId);
        if (step == null) {
            throw new IllegalArgumentException("Step not found with id: " + stepId);
        }
        // For a parent the current value is derived, so flipping it ticks (or un-ticks) the subtree.
        setCompletedDeep(step, !step.isCompleted(), Instant.now());

        return saveOrComplete(pursuit);
    }

    public LearningPursuit addStep(String pursuitId, AddPursuitStepRequest request) {
        log.info("Adding step to pursuit {} under parent {}", pursuitId, request.getParentId());
        LearningPursuit pursuit = findOwned(pursuitId);

        if (countSteps(pursuit.getSteps()) >= MAX_TOTAL_STEPS) {
            throw new IllegalArgumentException("A pursuit can have at most " + MAX_TOTAL_STEPS + " steps");
        }

        PursuitStep created = PursuitStep.builder()
                .id(UUID.randomUUID().toString())
                .text(request.getText().trim())
                .isCompleted(false)
                .build();

        if (request.getParentId() == null || request.getParentId().isBlank()) {
            pursuit.getSteps().add(created);
        } else {
            int parentDepth = depthOf(pursuit.getSteps(), request.getParentId(), 1);
            if (parentDepth < 0) {
                throw new IllegalArgumentException("Step not found with id: " + request.getParentId());
            }
            if (parentDepth >= MAX_DEPTH) {
                throw new IllegalArgumentException("Steps can only be nested " + MAX_DEPTH + " levels deep");
            }
            PursuitStep parent = findStep(pursuit.getSteps(), request.getParentId());
            childrenOf(parent).add(created);
        }

        // A new open step always leaves the pursuit (and its ancestors) incomplete.
        deriveCompletion(pursuit.getSteps());
        pursuit.setStatus("ACTIVE");
        return repository.save(pursuit);
    }

    public void deletePursuit(String id) {
        log.info("Deleting learning pursuit: {}", id);
        LearningPursuit existing = findOwned(id);
        repository.delete(existing);
        if (existing.isPrimary()) {
            promoteNextPrimary(existing.getUserId() != null ? existing.getUserId() : UserContext.getRequiredUserId());
        }
    }

    public LearningPursuit updatePursuit(String id, PursuitRequest request) {
        log.info("Updating learning pursuit metadata for id: {}", id);
        LearningPursuit existing = findOwned(id);

        existing.setTitle(request.getTitle().trim());
        existing.setCategory(request.getCategory());
        if (request.getGoal() != null) {
            existing.setGoal(blankToNull(request.getGoal()));
        }

        return repository.save(existing);
    }

    /** Makes this pursuit the user's only primary pursuit. */
    public LearningPursuit setPrimary(String id) {
        log.info("Setting primary learning pursuit: {}", id);
        LearningPursuit target = findOwned(id);
        String userId = UserContext.getRequiredUserId();

        List<LearningPursuit> changed = new ArrayList<>();
        for (LearningPursuit pursuit : repository.findByUserId(userId)) {
            boolean shouldBePrimary = pursuit.getId().equals(target.getId());
            if (pursuit.isPrimary() != shouldBePrimary) {
                pursuit.setPrimary(shouldBePrimary);
                changed.add(pursuit);
            }
        }
        if (!changed.isEmpty()) {
            repository.saveAll(changed);
        }
        target.setPrimary(true);
        return target;
    }

    public LearningPursuit deleteStep(String pursuitId, String stepId) {
        log.info("Deleting step {} from pursuit {}", stepId, pursuitId);
        LearningPursuit pursuit = findOwned(pursuitId);

        if (!removeStep(pursuit.getSteps(), stepId)) {
            throw new IllegalArgumentException("Step not found with id: " + stepId);
        }

        return saveOrComplete(pursuit);
    }

    /** Partial update: null fields are left alone (see UpdatePursuitStepRequest). */
    public LearningPursuit updateStep(String pursuitId, String stepId, UpdatePursuitStepRequest request) {
        log.info("Updating step {} in pursuit {}", stepId, pursuitId);
        if (request.getText() != null && request.getText().isBlank()) {
            throw new IllegalArgumentException("Step text cannot be blank");
        }

        LearningPursuit pursuit = findOwned(pursuitId);
        PursuitStep step = findStep(pursuit.getSteps(), stepId);
        if (step == null) {
            throw new IllegalArgumentException("Step not found with id: " + stepId);
        }

        if (request.getText() != null) {
            step.setText(truncate(request.getText().trim(), MAX_TEXT_LENGTH));
        }
        if (request.getEstimateMinutes() != null) {
            step.setEstimateMinutes(clampEstimate(request.getEstimateMinutes()));
        }
        if (request.getResumeNote() != null) {
            String note = blankToNull(request.getResumeNote());
            step.setResumeNote(note == null ? null : truncate(note, MAX_NOTE_LENGTH));
        }
        if (request.getTakeaways() != null) {
            step.setTakeaways(blankToNull(request.getTakeaways()));
        }

        return repository.save(pursuit);
    }

    /**
     * Adds focused minutes to a step. Called when a step-linked focus session completes;
     * a missing pursuit or step (deleted mid-session) is logged and skipped, never fatal.
     */
    public void creditStepTime(String userId, String pursuitId, String stepId, int minutes) {
        if (minutes <= 0) {
            return;
        }
        repository.findByIdAndUserId(pursuitId, userId).ifPresentOrElse(pursuit -> {
            PursuitStep step = findStep(pursuit.getSteps(), stepId);
            if (step == null) {
                log.warn("Focus credit skipped: step {} no longer in pursuit {}", stepId, pursuitId);
                return;
            }
            step.setSpentMinutes(step.getSpentMinutes() + minutes);
            repository.save(pursuit);
            log.info("Credited {}m to step {} in pursuit {}", minutes, stepId, pursuitId);
        }, () -> log.warn("Focus credit skipped: pursuit {} not found", pursuitId));
    }

    // ── Tree helpers ────────────────────────────────────────────────────────

    private LearningPursuit findOwned(String pursuitId) {
        String userId = UserContext.getRequiredUserId();
        return repository.findByIdAndUserId(pursuitId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Pursuit not found with id: " + pursuitId));
    }

    private void promoteNextPrimary(String userId) {
        repository.findByUserId(userId).stream()
                .filter(p -> !p.isPrimary())
                .min(Comparator.comparing(LearningPursuit::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .ifPresent(next -> {
                    next.setPrimary(true);
                    repository.save(next);
                    log.info("Promoted pursuit {} to primary", next.getId());
                });
    }

    private List<PursuitStep> buildSteps(List<PursuitStepInput> inputs, int depth) {
        List<PursuitStep> steps = new ArrayList<>();
        if (inputs == null) {
            return steps;
        }
        for (PursuitStepInput input : inputs) {
            if (input == null || input.getText() == null || input.getText().isBlank()) {
                continue;
            }
            boolean hasChildren = input.getChildren() != null
                    && input.getChildren().stream().anyMatch(c -> c != null && c.getText() != null && !c.getText().isBlank());
            if (hasChildren && depth >= MAX_DEPTH) {
                throw new IllegalArgumentException("Steps can only be nested " + MAX_DEPTH + " levels deep");
            }
            String note = blankToNull(input.getNote());
            steps.add(PursuitStep.builder()
                    .id(UUID.randomUUID().toString())
                    .text(truncate(input.getText().trim(), MAX_TEXT_LENGTH))
                    .note(note == null ? null : truncate(note, MAX_NOTE_LENGTH))
                    // Estimates live on leaves; a parent's time is the sum of its children.
                    .estimateMinutes(hasChildren || input.getEstimateMinutes() == null
                            ? null
                            : clampEstimate(input.getEstimateMinutes()))
                    .isCompleted(false)
                    .children(hasChildren ? buildSteps(input.getChildren(), depth + 1) : new ArrayList<>())
                    .build());
        }
        return steps;
    }

    /** Re-derives completion bottom-up, then either saves or migrates a finished pursuit. */
    private LearningPursuit saveOrComplete(LearningPursuit pursuit) {
        deriveCompletion(pursuit.getSteps());

        boolean allCompleted = !pursuit.getSteps().isEmpty()
                && pursuit.getSteps().stream().allMatch(PursuitStep::isCompleted);
        if (!allCompleted) {
            pursuit.setStatus("ACTIVE");
            return repository.save(pursuit);
        }

        String userId = pursuit.getUserId() != null ? pursuit.getUserId() : UserContext.getRequiredUserId();
        log.info("All steps completed for pursuit: {}. Moving to All Learnings log.", pursuit.getTitle());
        Learning learning = Learning.builder()
                .userId(userId)
                .title(pursuit.getTitle())
                .category(pursuit.getCategory())
                .date(LocalDate.now())
                .description(buildLearningDescription(pursuit))
                .notionUrl(pursuit.getNotionUrl())
                .build();
        learningRepository.save(learning);
        repository.delete(pursuit);
        if (pursuit.isPrimary()) {
            promoteNextPrimary(userId);
        }

        // Return the final representation so the client can announce the completion.
        pursuit.setStatus("COMPLETED");
        return pursuit;
    }

    private static void deriveCompletion(List<PursuitStep> steps) {
        for (PursuitStep step : steps) {
            List<PursuitStep> children = childrenOf(step);
            if (!children.isEmpty()) {
                deriveCompletion(children);
                step.setCompleted(children.stream().allMatch(PursuitStep::isCompleted));
            }
        }
    }

    private static void setCompletedDeep(PursuitStep step, boolean completed, Instant now) {
        step.setCompleted(completed);
        step.setCompletedAt(completed ? now : null);
        for (PursuitStep child : childrenOf(step)) {
            setCompletedDeep(child, completed, now);
        }
    }

    private static PursuitStep findStep(List<PursuitStep> steps, String stepId) {
        for (PursuitStep step : steps) {
            if (stepId.equals(step.getId())) {
                return step;
            }
            PursuitStep nested = findStep(childrenOf(step), stepId);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /** 1-based depth of the step, or -1 when it isn't in the tree. */
    private static int depthOf(List<PursuitStep> steps, String stepId, int depth) {
        for (PursuitStep step : steps) {
            if (stepId.equals(step.getId())) {
                return depth;
            }
            int nested = depthOf(childrenOf(step), stepId, depth + 1);
            if (nested > 0) {
                return nested;
            }
        }
        return -1;
    }

    private static boolean removeStep(List<PursuitStep> steps, String stepId) {
        if (steps.removeIf(step -> stepId.equals(step.getId()))) {
            return true;
        }
        for (PursuitStep step : steps) {
            if (removeStep(childrenOf(step), stepId)) {
                return true;
            }
        }
        return false;
    }

    private static int countSteps(List<PursuitStep> steps) {
        int count = 0;
        for (PursuitStep step : steps) {
            count += 1 + countSteps(childrenOf(step));
        }
        return count;
    }

    /** Documents stored before nesting have no children field; normalise to a mutable list. */
    private static List<PursuitStep> childrenOf(PursuitStep step) {
        if (step.getChildren() == null) {
            step.setChildren(new ArrayList<>());
        }
        return step.getChildren();
    }

    /** 0 (or less) clears; anything else lands on [MIN_ESTIMATE, MAX_ESTIMATE]. */
    private static Integer clampEstimate(int minutes) {
        if (minutes <= 0) {
            return null;
        }
        return Math.max(MIN_ESTIMATE, Math.min(MAX_ESTIMATE, minutes));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String buildLearningDescription(LearningPursuit pursuit) {
        StringBuilder sb = new StringBuilder();
        sb.append("Subtasks completed:\n");
        appendSteps(sb, pursuit.getSteps(), 0);
        if (pursuit.getNotionUrl() != null && !pursuit.getNotionUrl().isBlank()) {
            sb.append("\n").append(pursuit.getNotionUrl());
        }
        return sb.toString();
    }

    private static void appendSteps(StringBuilder sb, List<PursuitStep> steps, int indent) {
        for (PursuitStep step : steps) {
            String pad = "  ".repeat(indent);
            sb.append(pad).append("- ").append(step.getText()).append("\n");
            if (step.getTakeaways() != null && !step.getTakeaways().isBlank()) {
                for (String line : step.getTakeaways().split("\\R")) {
                    if (!line.isBlank()) {
                        sb.append(pad).append("    > ").append(line.trim()).append("\n");
                    }
                }
            }
            appendSteps(sb, childrenOf(step), indent + 1);
        }
    }
}
