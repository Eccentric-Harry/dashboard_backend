package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.PromptDTO;
import com.personal_dashboard.backend.dto.request.PromptRequest;
import com.personal_dashboard.backend.model.Prompt;
import com.personal_dashboard.backend.repository.PromptRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/prompts")
@RequiredArgsConstructor
public class PromptController {

        private final PromptRepository promptRepository;

        @GetMapping
        public ResponseEntity<ApiResponse<List<PromptDTO>>> getAllPrompts() {
                String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
                List<Prompt> prompts = promptRepository.findByUserId(userId, Sort.by(Sort.Direction.DESC, "updatedAt"));

                List<PromptDTO> dtos = prompts.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());

                ApiMeta meta = createMeta();
                ApiResponse<List<PromptDTO>> response = ApiResponse.<List<PromptDTO>>builder()
                                .data(dtos)
                                .meta(meta)
                                .build();

                return ResponseEntity.ok(response);
        }

        @PostMapping
        public ResponseEntity<ApiResponse<PromptDTO>> createPrompt(
                        @Valid @RequestBody PromptRequest request) {

                Instant now = Instant.now();

                Prompt prompt = Prompt.builder()
                                .title(request.getTitle())
                                .content(request.getContent())
                                .category(request.getCategory())
                                .tags(request.getTags())
                                .createdAt(now)
                                .updatedAt(now)
                                .build();

                Prompt savedPrompt = promptRepository.save(prompt);
                log.info("Created prompt {} '{}'", savedPrompt.getId(), savedPrompt.getTitle());

                ApiResponse<PromptDTO> response = ApiResponse.<PromptDTO>builder()
                                .data(mapToDTO(savedPrompt))
                                .meta(createMeta())
                                .build();

                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @PutMapping("/{id}")
        public ResponseEntity<ApiResponse<PromptDTO>> updatePrompt(
                        @PathVariable String id,
                        @Valid @RequestBody PromptRequest request) {

                String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
                Prompt existingPrompt = promptRepository.findByIdAndUserId(id, userId)
                                .orElseThrow(() -> new RuntimeException("Prompt not found: " + id));

                existingPrompt.setTitle(request.getTitle());
                existingPrompt.setContent(request.getContent());
                existingPrompt.setCategory(request.getCategory());
                existingPrompt.setTags(request.getTags());
                existingPrompt.setUpdatedAt(Instant.now());

                Prompt savedPrompt = promptRepository.save(existingPrompt);
                log.info("Updated prompt {}", id);

                ApiResponse<PromptDTO> response = ApiResponse.<PromptDTO>builder()
                                .data(mapToDTO(savedPrompt))
                                .meta(createMeta())
                                .build();

                return ResponseEntity.ok(response);
        }

        @DeleteMapping("/{id}")
        public ResponseEntity<ApiResponse<Void>> deletePrompt(@PathVariable String id) {
                String userId = com.personal_dashboard.backend.security.UserContext.getRequiredUserId();
                Prompt existingPrompt = promptRepository.findByIdAndUserId(id, userId)
                                .orElseThrow(() -> new RuntimeException("Prompt not found: " + id));

                log.info("Deleting prompt {}", id);
                promptRepository.delete(existingPrompt);

                ApiResponse<Void> response = ApiResponse.<Void>builder()
                                .data(null)
                                .meta(createMeta())
                                .build();

                return ResponseEntity.ok(response);
        }

        private PromptDTO mapToDTO(Prompt prompt) {
                return PromptDTO.builder()
                                .id(prompt.getId())
                                .title(prompt.getTitle())
                                .content(prompt.getContent())
                                .category(prompt.getCategory())
                                .tags(prompt.getTags())
                                .createdAt(prompt.getCreatedAt())
                                .updatedAt(prompt.getUpdatedAt())
                                .build();
        }

        private ApiMeta createMeta() {
                return ApiMeta.builder()
                                .requestId(UUID.randomUUID().toString())
                                .timestamp(Instant.now().toString())
                                .source("api")
                                .build();
        }
}
