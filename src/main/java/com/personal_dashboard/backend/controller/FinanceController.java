package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.TransactionDTO;
import com.personal_dashboard.backend.dto.request.TransactionRequest;
import com.personal_dashboard.backend.model.Transaction;
import com.personal_dashboard.backend.repository.TransactionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final TransactionRepository transactionRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @PostMapping("/transactions")
    public ResponseEntity<ApiResponse<TransactionDTO>> createTransaction(
            @Valid @RequestBody TransactionRequest request) {

        // Parse the date string to LocalDate, then convert to Instant
        LocalDate localDate = LocalDate.parse(request.getDate(), DATE_FORMATTER);
        Instant dateInstant = localDate.atStartOfDay()
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant();

        // Create and save the transaction
        Transaction transaction = Transaction.builder()
                .description(request.getDescription())
                .amount(request.getAmount())
                .category(request.getCategory())
                .type(request.getType())
                .date(dateInstant)
                .build();

        Transaction savedTransaction = transactionRepository.save(transaction);

        // Map to DTO
        TransactionDTO responseDto = TransactionDTO.builder()
                .id(savedTransaction.getId())
                .description(savedTransaction.getDescription())
                .amount(savedTransaction.getAmount().doubleValue())
                .category(savedTransaction.getCategory())
                .type(savedTransaction.getType())
                .date(savedTransaction.getDate().toString())
                .build();

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<TransactionDTO> response = ApiResponse.<TransactionDTO>builder()
                .data(responseDto)
                .meta(meta)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<List<TransactionDTO>>> getTransactions(
            @RequestParam(value = "days", defaultValue = "30") int days,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "type", required = false) String type) {

        Instant endDate = Instant.now();
        Instant startDate = endDate.minus(Duration.ofDays(days));

        List<Transaction> transactions;

        if (type != null && !type.isEmpty() && category != null && !category.isEmpty()) {
            transactions = transactionRepository.findByTypeAndDateBetween(type, startDate, endDate);
            transactions = transactions.stream()
                    .filter(t -> t.getCategory().equalsIgnoreCase(category))
                    .toList();
        } else if (category != null && !category.isEmpty()) {
            transactions = transactionRepository.findByCategoryAndDateBetween(category, startDate, endDate);
        } else if (type != null && !type.isEmpty()) {
            transactions = transactionRepository.findByTypeAndDateBetween(type, startDate, endDate);
        } else {
            transactions = transactionRepository.findByDateBetween(startDate, endDate);
        }

        List<TransactionDTO> dtos = transactions.stream()
                .map(t -> TransactionDTO.builder()
                        .id(t.getId())
                        .description(t.getDescription())
                        .amount(t.getAmount().doubleValue())
                        .category(t.getCategory())
                        .type(t.getType())
                        .date(t.getDate().toString())
                        .build())
                .toList();

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<List<TransactionDTO>> response = ApiResponse.<List<TransactionDTO>>builder()
                .data(dtos)
                .meta(meta)
                .build();

        return ResponseEntity.ok(response);
    }
}
