package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What a pasted product link says, to prefill the add form. {@code fetched} = the page itself was read. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkPreviewDTO {
    private String url;
    private String title;
    private String imageUrl;
    private String store;
    private Double price;
    private boolean fetched;
}
