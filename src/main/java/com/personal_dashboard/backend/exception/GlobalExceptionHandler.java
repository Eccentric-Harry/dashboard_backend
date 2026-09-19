package com.personal_dashboard.backend.exception;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    /** A malformed {@code ?date=} (or similar) is the caller's mistake, not a server fault. */
    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleDateTimeParse(DateTimeParseException ex) {
        log.warn("Unparseable date/time '{}': {}", ex.getParsedString(), ex.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, "Invalid date or time format (expected ISO, e.g. 2026-09-19)", null);
    }

    /**
     * The database was unreachable or too slow (see MongoClientConfig's bounds). This is
     * transient, so answer 503 + Retry-After — the client retries GETs on it — instead of
     * a generic 500, and keep driver internals out of the response body.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessResourceException.class,
            QueryTimeoutException.class, MongoTimeoutException.class, MongoSocketException.class})
    public ResponseEntity<ApiResponse<Map<String, String>>> handleDatabaseUnavailable(Exception ex) {
        String requestId = UUID.randomUUID().toString();
        log.error("Database unavailable [requestId={}]: {}", requestId, ex.getMessage(), ex);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                "The database is temporarily unavailable. Please retry.", requestId);
    }

    private ResponseEntity<ApiResponse<Map<String, String>>> errorResponse(
            HttpStatus status, String message, String requestId) {
        Map<String, String> errorDetails = new HashMap<>();
        errorDetails.put("message", message);

        ApiMeta meta = ApiMeta.builder()
                .requestId(requestId != null ? requestId : UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        var builder = ResponseEntity.status(status);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            builder.header(HttpHeaders.RETRY_AFTER, "2");
        }
        return builder.body(ApiResponse.<Map<String, String>>builder()
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
