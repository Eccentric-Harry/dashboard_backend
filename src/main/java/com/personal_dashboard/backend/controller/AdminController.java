package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.service.CsvImportService;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@AllArgsConstructor
public class AdminController {

    private final CsvImportService csvImportService;

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> importCsvFiles(
            @RequestParam(value = "foodCsv", required = false) MultipartFile foodCsv,
            @RequestParam(value = "spendingCsv", required = false) MultipartFile spendingCsv) {
        try {
            Map<String, Object> result = csvImportService.importCsvFiles(foodCsv, spendingCsv);

            ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                    .data(result)
                    .build();

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = Map.of("error", e.getMessage());

            ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                    .data(error)
                    .build();

            return ResponseEntity.badRequest().body(response);
        }
    }
}
