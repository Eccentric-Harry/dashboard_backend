package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.request.RunSessionRequest;
import com.personal_dashboard.backend.model.RunSession;
import com.personal_dashboard.backend.repository.RunSessionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fitness")
@RequiredArgsConstructor
public class FitnessController {

    private final RunSessionRepository runSessionRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createRunSession(
            @Valid @RequestBody RunSessionRequest request) {

        LocalDate localDate = LocalDate.parse(request.getDate(), DATE_FORMATTER);

        // Calculate average pace (minutes per km)
        double paceMinutesPerKm = (double) request.getDurationMinutes() / request.getDistanceKm();
        int paceMinutes = (int) Math.floor(paceMinutesPerKm);
        int paceSeconds = (int) ((paceMinutesPerKm - paceMinutes) * 60);
        String averagePace = String.format("%d:%02d", paceMinutes, paceSeconds);

        // Format moving time (HH:MM:SS)
        int hours = request.getDurationMinutes() / 60;
        int minutes = request.getDurationMinutes() % 60;
        String movingTime = String.format("%02d:%02d:00", hours, minutes);

        // Create and save the run session
        RunSession runSession = RunSession.builder()
                .title("Run on " + localDate)
                .date(localDate)
                .distanceKm(request.getDistanceKm())
                .movingTime(movingTime)
                .averagePace(averagePace)
                .elevationMeters(request.getElevationGainMeters())
                .build();

        RunSession savedSession = runSessionRepository.save(runSession);

        // Build response map
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("id", savedSession.getId());
        responseData.put("title", savedSession.getTitle());
        responseData.put("date", savedSession.getDate().format(DATE_FORMATTER));
        responseData.put("distanceKm", savedSession.getDistanceKm());
        responseData.put("movingTime", savedSession.getMovingTime());
        responseData.put("averagePace", savedSession.getAveragePace());
        responseData.put("elevationMeters", savedSession.getElevationMeters());

        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .data(responseData)
                .meta(meta)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
