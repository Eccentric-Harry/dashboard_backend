package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.LendingRecordDTO;
import com.personal_dashboard.backend.dto.request.LendingRecordRequest;
import com.personal_dashboard.backend.model.LendingRecord;
import com.personal_dashboard.backend.repository.LendingRecordRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/finance/lending")
@RequiredArgsConstructor
public class LendingController {

    private final LendingRecordRepository lendingRecordRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @GetMapping
    public ResponseEntity<ApiResponse<List<LendingRecordDTO>>> getAllLendingRecords() {
        List<LendingRecord> records = lendingRecordRepository.findAll(Sort.by(Sort.Direction.DESC, "date"));

        List<LendingRecordDTO> dtos = records.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<List<LendingRecordDTO>> response = ApiResponse.<List<LendingRecordDTO>>builder()
                .data(dtos)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LendingRecordDTO>> createLendingRecord(
            @Valid @RequestBody LendingRecordRequest request) {

        Instant dateInstant = parseDate(request.getDate());
        Instant dueDateInstant = parseDate(request.getDueDate());

        LendingRecord record = LendingRecord.builder()
                .borrower(request.getBorrower())
                .amount(request.getAmount())
                .date(dateInstant)
                .dueDate(dueDateInstant)
                .status(request.getStatus())
                .notes(request.getNotes())
                .build();

        LendingRecord savedRecord = lendingRecordRepository.save(record);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<LendingRecordDTO> response = ApiResponse.<LendingRecordDTO>builder()
                .data(mapToDTO(savedRecord))
                .meta(meta)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<LendingRecordDTO>> updateLendingRecord(
            @PathVariable String id,
            @Valid @RequestBody LendingRecordRequest request) {

        LendingRecord existingRecord = lendingRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Lending record not found: " + id));

        Instant dateInstant = parseDate(request.getDate());
        Instant dueDateInstant = parseDate(request.getDueDate());

        existingRecord.setBorrower(request.getBorrower());
        existingRecord.setAmount(request.getAmount());
        existingRecord.setDate(dateInstant);
        existingRecord.setDueDate(dueDateInstant);
        existingRecord.setStatus(request.getStatus());
        existingRecord.setNotes(request.getNotes());

        LendingRecord savedRecord = lendingRecordRepository.save(existingRecord);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<LendingRecordDTO> response = ApiResponse.<LendingRecordDTO>builder()
                .data(mapToDTO(savedRecord))
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<ApiResponse<LendingRecordDTO>> toggleLendingRecordStatus(@PathVariable String id) {
        LendingRecord existingRecord = lendingRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Lending record not found: " + id));

        if ("Repaid".equalsIgnoreCase(existingRecord.getStatus())) {
            existingRecord.setStatus("Pending");
        } else {
            existingRecord.setStatus("Repaid");
        }

        LendingRecord savedRecord = lendingRecordRepository.save(existingRecord);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<LendingRecordDTO> response = ApiResponse.<LendingRecordDTO>builder()
                .data(mapToDTO(savedRecord))
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteLendingRecord(@PathVariable String id) {
        LendingRecord existingRecord = lendingRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Lending record not found: " + id));

        lendingRecordRepository.delete(existingRecord);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .data(null)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }

    private Instant parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        LocalDate localDate = LocalDate.parse(dateStr, DATE_FORMATTER);
        return localDate.atStartOfDay()
                .atZone(ZoneId.systemDefault())
                .toInstant();
    }

    private LendingRecordDTO mapToDTO(LendingRecord record) {
        return LendingRecordDTO.builder()
                .id(record.getId())
                .borrower(record.getBorrower())
                .amount(record.getAmount() != null ? record.getAmount().doubleValue() : 0.0)
                .date(record.getDate() != null ? record.getDate().toString() : null)
                .dueDate(record.getDueDate() != null ? record.getDueDate().toString() : null)
                .status(record.getStatus())
                .notes(record.getNotes())
                .build();
    }
}
