package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Curate a goal's showcase. A null list leaves that part as it is. Photos and highlights can
 * only be reordered or removed here (new photos come in through find, which measures them);
 * reasons are the user's own words and are replaced as given.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowcaseUpdateRequest {

    /** Photo URLs in display order; the first is the cover. */
    @Size(max = 24, message = "Too many photos")
    private List<String> photos;

    @Size(max = 8, message = "Too many highlights")
    private List<String> highlights;

    @Size(max = 6, message = "Six reasons is plenty")
    private List<@Size(max = 140, message = "Keep each reason under 140 characters") String> reasons;
}
