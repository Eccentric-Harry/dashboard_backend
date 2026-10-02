package com.personal_dashboard.backend.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** The buddy's name, what it wears and which decorations are out — all replaced at once. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampLookRequest {

    /** Blank resets to the default, "Pip". Letters, spaces, apostrophes and hyphens. */
    @Size(max = 16, message = "a name is at most 16 characters")
    @Pattern(regexp = "^$|^[\\p{L}][\\p{L} '\\-]{0,15}$", message = "a name uses letters, spaces, ' and -")
    private String buddyName;

    /** slot (hat, neck, face) → owned item id. */
    private Map<String, String> equipped;

    @Size(max = 12, message = "too many decorations")
    private List<String> decor;
}
