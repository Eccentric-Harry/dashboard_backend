package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkPreviewRequest {

    @NotBlank(message = "Paste a link first")
    @Size(max = 2000, message = "Link is too long")
    private String url;
}
