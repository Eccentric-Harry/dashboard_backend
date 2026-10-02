package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** One page of a goal world's kit (see GoalKit). Empty picks and fields clear the page. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalKitPageRequest {

    @Size(max = 12, message = "pick at most 12")
    private List<@NotNull @Size(max = 60, message = "a pick must be at most 60 characters") String> picks;

    @Size(max = 6, message = "at most 6 fields")
    private Map<
            @Pattern(regexp = "^[a-z-]{1,20}$", message = "field keys must be short lowercase keys") String,
            @Size(max = 300, message = "each field must be at most 300 characters") String> fields;
}
