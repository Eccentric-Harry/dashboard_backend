package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.LinkPreviewDTO;
import com.personal_dashboard.backend.dto.WishlistItemDTO;
import com.personal_dashboard.backend.exception.GlobalExceptionHandler;
import com.personal_dashboard.backend.service.WishlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WishlistControllerTest {

    private WishlistService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(WishlistService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WishlistController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void previewNeedsALink() throws Exception {
        mvc.perform(post("/api/v1/wishlist/preview").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void previewAnswersWithWhatTheLinkSays() throws Exception {
        when(service.preview("amazon.in/dp/X")).thenReturn(LinkPreviewDTO.builder().store("Amazon").fetched(false).build());
        mvc.perform(post("/api/v1/wishlist/preview").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"amazon.in/dp/X\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store").value("Amazon"));
    }

    @Test
    void createValidatesPriorityAndPrice() throws Exception {
        mvc.perform(post("/api/v1/wishlist").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Shoes\",\"priority\":\"URGENT\",\"price\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors.priority").exists())
                .andExpect(jsonPath("$.data.errors.price").exists());
    }

    @Test
    void createReturns201() throws Exception {
        when(service.create(any())).thenReturn(WishlistItemDTO.builder().id("w1").build());
        mvc.perform(post("/api/v1/wishlist").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Shoes\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("w1"));
    }

    @Test
    void buyNeedsAPositivePrice() throws Exception {
        mvc.perform(post("/api/v1/wishlist/w1/buy").contentType(MediaType.APPLICATION_JSON).content("{\"price\":0}"))
                .andExpect(status().isBadRequest());
        when(service.buy(eq("w1"), any())).thenReturn(WishlistItemDTO.builder().id("w1").status("BOUGHT").build());
        mvc.perform(post("/api/v1/wishlist/w1/buy").contentType(MediaType.APPLICATION_JSON).content("{\"price\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BOUGHT"));
    }
}
