package com.personal_dashboard.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
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

    @Size(max = 30, message = "too many decorations")
    private List<String> decor;

    /** Where meadow decorations stand. Omitted keeps the current spots. */
    @Size(max = 30, message = "too many spots")
    private Map<String, @Valid Spot> decorAt;

    /** Fractions across the meadow's width (x) and down its depth (y). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Spot {
        @NotNull
        @DecimalMin(value = "0.0", message = "that spot is outside the camp")
        @DecimalMax(value = "1.0", message = "that spot is outside the camp")
        private Double x;

        @NotNull
        @DecimalMin(value = "0.0", message = "that spot is outside the camp")
        @DecimalMax(value = "1.0", message = "that spot is outside the camp")
        private Double y;
    }
}
