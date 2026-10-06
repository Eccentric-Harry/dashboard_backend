package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ShoppingItemDTO;
import com.personal_dashboard.backend.exception.GlobalExceptionHandler;
import com.personal_dashboard.backend.service.ShoppingListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract: routes, status codes and request validation, through the real exception handler. */
@ExtendWith(MockitoExtension.class)
class ShoppingControllerTest {

    @Mock
    private ShoppingListService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ShoppingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static ShoppingItemDTO dto(String id, String name) {
        return ShoppingItemDTO.builder().id(id).name(name).category("DAIRY").build();
    }

    @Test
    void listReturnsTheEnvelope() throws Exception {
        when(service.list()).thenReturn(List.of(dto("a", "Milk")));

        mvc.perform(get("/api/v1/shopping/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Milk"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void addReturns201WithTheItem() throws Exception {
        when(service.addAll(any())).thenReturn(List.of(dto("a", "Milk")));

        mvc.perform(post("/api/v1/shopping/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"milk\",\"quantity\":\"2 L\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("a"));
    }

    @Test
    void addRejectsABlankName() throws Exception {
        mvc.perform(post("/api/v1/shopping/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors.name").exists());
        verifyNoInteractions(service);
    }

    @Test
    void addRejectsOverlongFields() throws Exception {
        mvc.perform(post("/api/v1/shopping/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(81) + "\",\"quantity\":\"" + "9".repeat(25) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors.name").exists())
                .andExpect(jsonPath("$.data.errors.quantity").exists());
    }

    @Test
    void batchValidatesEachItemAndTheSize() throws Exception {
        mvc.perform(post("/api/v1/shopping/items/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/shopping/items/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"ok\"},{\"name\":\"\"}]}"))
                .andExpect(status().isBadRequest());

        StringBuilder tooMany = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 51; i++) {
            tooMany.append(i == 0 ? "" : ",").append("{\"name\":\"item").append(i).append("\"}");
        }
        mvc.perform(post("/api/v1/shopping/items/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(tooMany.append("]}").toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void batchAddsAndReturns201() throws Exception {
        when(service.addAll(any())).thenReturn(List.of(dto("a", "Milk"), dto("b", "Bread")));

        mvc.perform(post("/api/v1/shopping/items/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"milk\"},{\"name\":\"bread\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void aServiceRefusalBecomesA400WithItsMessage() throws Exception {
        when(service.update(eq("zzz"), any())).thenThrow(new IllegalArgumentException("Shopping item not found: zzz"));

        mvc.perform(put("/api/v1/shopping/items/zzz").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Milk\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("Shopping item not found: zzz"));
    }

    @Test
    void checkedNeedsAValue() throws Exception {
        mvc.perform(patch("/api/v1/shopping/items/a/checked").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        when(service.setChecked("a", true)).thenReturn(dto("a", "Milk"));
        mvc.perform(patch("/api/v1/shopping/items/a/checked").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void clearCheckedIsNotMistakenForAnItemId() throws Exception {
        when(service.clearChecked()).thenReturn(List.of(dto("a", "Milk")));

        mvc.perform(post("/api/v1/shopping/items/clear-checked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("a"));
        verify(service, never()).delete(any());
    }

    @Test
    void deleteDelegatesToTheService() throws Exception {
        mvc.perform(delete("/api/v1/shopping/items/a")).andExpect(status().isOk());
        verify(service).delete("a");
    }
}
