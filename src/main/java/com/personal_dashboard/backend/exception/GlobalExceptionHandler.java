package com.personal_dashboard.backend.exception;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleValidationException(
            MethodArgumentNotValidException ex) {

        log.warn("Validation error: {}", ex.getMessage());

        Map<String, Object> errorDetails = new HashMap<>();
        Map<String, String> fieldErrors = new HashMap<>();

        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.put(fieldName, errorMessage);
        });

        errorDetails.put("message", "Validation failed");
        errorDetails.put("errors", fieldErrors);

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Map<String, Object>>builder()
                        .data(errorDetails)
                        .meta(meta)
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleIllegalArgument(
            IllegalArgumentException ex) {

        log.warn("Illegal argument: {}", ex.getMessage());

        Map<String, String> errorDetails = new HashMap<>();
        errorDetails.put("message", ex.getMessage());

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Map<String, String>>builder()
                        .data(errorDetails)
                        .meta(meta)
                        .build());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleNoHandlerFound(
            NoHandlerFoundException ex) {

        log.warn("Resource not found: {}", ex.getRequestURL());

        Map<String, String> errorDetails = new HashMap<>();
        errorDetails.put("message", "Resource not found");
        errorDetails.put("path", ex.getRequestURL());

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.<Map<String, String>>builder()
                        .data(errorDetails)
                        .meta(meta)
                        .build());
    }
}
