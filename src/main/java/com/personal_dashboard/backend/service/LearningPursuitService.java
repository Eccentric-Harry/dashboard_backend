package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.PursuitRequest;
import com.personal_dashboard.backend.model.Learning;
import com.personal_dashboard.backend.model.LearningPursuit;
import com.personal_dashboard.backend.model.LearningPursuit.PursuitStep;
import com.personal_dashboard.backend.repository.LearningPursuitRepository;
import com.personal_dashboard.backend.repository.LearningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LearningPursuitService {

    private final LearningPursuitRepository repository;
    private final LearningRepository learningRepository;
    private final NotionIntegrationService notionService;

    public List<LearningPursuit> getAllPursuits() {
        log.info("Retrieving all learning pursuits");
        return repository.findAll();
    }

    public LearningPursuit createPursuit(PursuitRequest request) {
        log.info("Creating pursuit model for: {}", request.getTitle());

        // Create the Notion page dynamically
        String notionUrl = notionService.createNotionPage(request.getTitle());

        // Construct steps
        List<PursuitStep> steps = new ArrayList<>();
        if (request.getSteps() != null) {
            for (String stepText : request.getSteps()) {
                if (stepText != null && !stepText.isBlank()) {
                    steps.add(PursuitStep.builder()
                            .id(UUID.randomUUID().toString())
                            .text(stepText.trim())
                            .isCompleted(false)
                            .build());
                }
            }
        }

        LearningPursuit pursuit = LearningPursuit.builder()
                .title(request.getTitle())
                .category(request.getCategory())
                .notionUrl(notionUrl)
                .status("ACTIVE")
                .steps(steps)
                .build();

        return repository.save(pursuit);
    }

    public LearningPursuit toggleStep(String pursuitId, String stepId) {
        log.info("Toggling subtask step {} within pursuit {}", stepId, pursuitId);

        LearningPursuit pursuit = repository.findById(pursuitId)
                .orElseThrow(() -> new IllegalArgumentException("Pursuit not found with id: " + pursuitId));

        boolean stepFound = false;
        boolean allCompleted = true;

        for (PursuitStep step : pursuit.getSteps()) {
            if (step.getId().equals(stepId)) {
                step.setCompleted(!step.isCompleted());
                stepFound = true;
            }
            if (!step.isCompleted()) {
                allCompleted = false;
            }
        }

        if (!stepFound) {
            throw new IllegalArgumentException("Step not found with id: " + stepId);
        }

        // Auto-update pursuit status based on step completion
        if (allCompleted && !pursuit.getSteps().isEmpty()) {
            log.info("All subtasks completed for pursuit: {}. Moving to All Learnings log.", pursuit.getTitle());
            
            // 1. Create a Learning log in learnings database collection
            Learning learning = Learning.builder()
                    .title(pursuit.getTitle())
                    .category(pursuit.getCategory())
                    .date(LocalDate.now())
                    .description(buildLearningDescription(pursuit))
                    .notionUrl(pursuit.getNotionUrl())
                    .build();
            learningRepository.save(learning);

            // 2. Remove it from learning_pursuits queue collection
            repository.deleteById(pursuitId);

            // Set state to COMPLETED and return the final representation to client
            pursuit.setStatus("COMPLETED");
            return pursuit;
        } else {
            pursuit.setStatus("ACTIVE");
            return repository.save(pursuit);
        }
    }

    public void deletePursuit(String id) {
        log.info("Deleting learning pursuit: {}", id);
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("Pursuit not found with id: " + id);
        }
        repository.deleteById(id);
    }

    public LearningPursuit updatePursuit(String id, PursuitRequest request) {
        log.info("Updating learning pursuit metadata for id: {}", id);
        LearningPursuit existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pursuit not found with id: " + id));

        existing.setTitle(request.getTitle().trim());
        existing.setCategory(request.getCategory());

        return repository.save(existing);
    }

    public LearningPursuit deleteStep(String pursuitId, String stepId) {
        log.info("Deleting step {} from pursuit {}", stepId, pursuitId);
        LearningPursuit pursuit = repository.findById(pursuitId)
                .orElseThrow(() -> new IllegalArgumentException("Pursuit not found with id: " + pursuitId));

        boolean removed = pursuit.getSteps().removeIf(step -> step.getId().equals(stepId));
        if (!removed) {
            throw new IllegalArgumentException("Step not found with id: " + stepId);
        }

        // Re-evaluate overall status if steps list changed
        boolean allCompleted = true;
        for (PursuitStep step : pursuit.getSteps()) {
            if (!step.isCompleted()) {
                allCompleted = false;
            }
        }
        
        if (allCompleted && !pursuit.getSteps().isEmpty()) {
            log.info("Subtasks deletion resulted in all remaining steps completed. Moving to All Learnings.");
            
            Learning learning = Learning.builder()
                    .title(pursuit.getTitle())
                    .category(pursuit.getCategory())
                    .date(LocalDate.now())
                    .description(buildLearningDescription(pursuit))
                    .notionUrl(pursuit.getNotionUrl())
                    .build();
            learningRepository.save(learning);
            repository.deleteById(pursuitId);
            
            pursuit.setStatus("COMPLETED");
            return pursuit;
        } else {
            pursuit.setStatus("ACTIVE");
            return repository.save(pursuit);
        }
    }

    public LearningPursuit updateStep(String pursuitId, String stepId, String newText) {
        log.info("Updating step {} text to '{}' in pursuit {}", stepId, newText, pursuitId);
        if (newText == null || newText.isBlank()) {
            throw new IllegalArgumentException("Step text cannot be blank");
        }

        LearningPursuit pursuit = repository.findById(pursuitId)
                .orElseThrow(() -> new IllegalArgumentException("Pursuit not found with id: " + pursuitId));

        boolean found = false;
        for (PursuitStep step : pursuit.getSteps()) {
            if (step.getId().equals(stepId)) {
                step.setText(newText.trim());
                found = true;
                break;
            }
        }

        if (!found) {
            throw new IllegalArgumentException("Step not found with id: " + stepId);
        }

        return repository.save(pursuit);
    }

    private String buildLearningDescription(LearningPursuit pursuit) {
        StringBuilder sb = new StringBuilder();
        sb.append("Subtasks completed:\n");
        for (LearningPursuit.PursuitStep step : pursuit.getSteps()) {
            sb.append("- ").append(step.getText()).append("\n");
        }
        if (pursuit.getNotionUrl() != null && !pursuit.getNotionUrl().isBlank()) {
            sb.append("\n").append(pursuit.getNotionUrl());
        }
        return sb.toString();
    }
}
