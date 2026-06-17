package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.LendingRecordDTO;
import com.personal_dashboard.backend.dto.request.LendingRecordRequest;
import com.personal_dashboard.backend.model.LendingRecord;
import com.personal_dashboard.backend.repository.LendingRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LendingControllerTest {

    @Mock
    private LendingRecordRepository lendingRecordRepository;

    @InjectMocks
    private LendingController lendingController;

    private LendingRecord lendingRecord;

    @BeforeEach
    void setUp() {
        lendingRecord = LendingRecord.builder()
                .id("test-id")
                .borrower("Alice")
                .amount(new BigDecimal("1500.00"))
                .date(Instant.parse("2026-06-12T00:00:00Z"))
                .dueDate(Instant.parse("2026-06-20T00:00:00Z"))
                .status("Pending")
                .notes("Friend")
                .build();
    }

    @Test
    void testGetAllLendingRecords() {
        List<LendingRecord> list = List.of(lendingRecord);
        when(lendingRecordRepository.findAll(any(Sort.class))).thenReturn(list);

        ResponseEntity<ApiResponse<List<LendingRecordDTO>>> response = lendingController.getAllLendingRecords();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getData().size());
        assertEquals("Alice", response.getBody().getData().getFirst().getBorrower());
    }

    @Test
    void testCreateLendingRecord() {
        LendingRecordRequest request = LendingRecordRequest.builder()
                .borrower("Alice")
                .amount(new BigDecimal("1500.00"))
                .date("2026-06-12")
                .dueDate("2026-06-20")
                .status("Pending")
                .notes("Friend")
                .build();

        when(lendingRecordRepository.save(any(LendingRecord.class))).thenReturn(lendingRecord);

        ResponseEntity<ApiResponse<LendingRecordDTO>> response = lendingController.createLendingRecord(request);

        assertNotNull(response);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        LendingRecordDTO data = response.getBody().getData();
        assertEquals("Alice", data.getBorrower());
        assertEquals(1500.0, data.getAmount());
    }

    @Test
    void testUpdateLendingRecord() {
        LendingRecordRequest request = LendingRecordRequest.builder()
                .borrower("Alice Updated")
                .amount(new BigDecimal("2000.00"))
                .date("2026-06-12")
                .dueDate("2026-06-20")
                .status("Pending")
                .notes("Friend Updated")
                .build();

        LendingRecord updatedRecord = LendingRecord.builder()
                .id("test-id")
                .borrower("Alice Updated")
                .amount(new BigDecimal("2000.00"))
                .date(Instant.parse("2026-06-12T00:00:00Z"))
                .dueDate(Instant.parse("2026-06-20T00:00:00Z"))
                .status("Pending")
                .notes("Friend Updated")
                .build();

        when(lendingRecordRepository.findById("test-id")).thenReturn(Optional.of(lendingRecord));
        when(lendingRecordRepository.save(any(LendingRecord.class))).thenReturn(updatedRecord);

        ResponseEntity<ApiResponse<LendingRecordDTO>> response = lendingController.updateLendingRecord("test-id",
                request);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        LendingRecordDTO data = response.getBody().getData();
        assertEquals("Alice Updated", data.getBorrower());
        assertEquals(2000.0, data.getAmount());
    }

    @Test
    void testToggleLendingRecordStatus() {
        LendingRecord toggledRecord = LendingRecord.builder()
                .id("test-id")
                .borrower("Alice")
                .amount(new BigDecimal("1500.00"))
                .date(Instant.parse("2026-06-12T00:00:00Z"))
                .status("Repaid")
                .build();

        when(lendingRecordRepository.findById("test-id")).thenReturn(Optional.of(lendingRecord));
        when(lendingRecordRepository.save(any(LendingRecord.class))).thenReturn(toggledRecord);

        ResponseEntity<ApiResponse<LendingRecordDTO>> response = lendingController.toggleLendingRecordStatus("test-id");

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        LendingRecordDTO data = response.getBody().getData();
        assertEquals("Repaid", data.getStatus());
    }

    @Test
    void testDeleteLendingRecord() {
        when(lendingRecordRepository.findById("test-id")).thenReturn(Optional.of(lendingRecord));
        doNothing().when(lendingRecordRepository).delete(any(LendingRecord.class));

        ResponseEntity<ApiResponse<Void>> response = lendingController.deleteLendingRecord("test-id");

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lendingRecordRepository, times(1)).delete(any(LendingRecord.class));
    }
}
