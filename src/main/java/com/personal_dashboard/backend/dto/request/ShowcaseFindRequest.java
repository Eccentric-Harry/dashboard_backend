package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fill a goal's showcase. With a link: a product page brings its photos and highlights, an
 * image link adds that one photo. Without: the official page is looked up from the goal's name.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowcaseFindRequest {

    @Size(max = 2048, message = "That link is too long")
    private String url;
}
