package com.personal_dashboard.backend.service;

import com.personal_dashboard.backend.dto.request.LearningRequest;
import com.personal_dashboard.backend.model.Learning;
import com.personal_dashboard.backend.repository.LearningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LearningService {

    private final LearningRepository learningRepository;

    public List<Learning> getLearningsForDate(LocalDate date) {
        log.info("Fetching learnings for date: {}", date);
        return learningRepository.findByDate(date);
    }

    public List<Learning> getLearningsForRange(LocalDate startDate, LocalDate endDate) {
        log.info("Fetching learnings between {} and {}", startDate, endDate);
        return learningRepository.findByDateBetween(startDate, endDate);
    }

    public List<Learning> getAllLearnings() {
        log.info("Fetching all learnings");
        return learningRepository.findAll();
    }

    public Learning createLearning(LearningRequest request) {
        log.info("Creating new learning: {}", request.getTitle());
        
        Learning learning = Learning.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .category(request.getCategory())
                .date(LocalDate.parse(request.getDate()))
                .build();

        return learningRepository.save(learning);
    }

    public Learning updateLearning(String id, LearningRequest request) {
        log.info("Updating learning id: {}", id);

        Learning existing = learningRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Learning not found with id: " + id));

        existing.setTitle(request.getTitle());
        existing.setDescription(request.getDescription());
        existing.setCategory(request.getCategory());
        existing.setDate(LocalDate.parse(request.getDate()));

        return learningRepository.save(existing);
    }

    public void deleteLearning(String id) {
        log.info("Deleting learning id: {}", id);
        if (!learningRepository.existsById(id)) {
            throw new IllegalArgumentException("Learning not found with id: " + id);
        }
        learningRepository.deleteById(id);
    }
}
