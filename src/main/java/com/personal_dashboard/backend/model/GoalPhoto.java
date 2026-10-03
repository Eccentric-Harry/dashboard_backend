package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One photo in a goal's showcase. Size and tone are measured when the photo is found so the
 * client can lay it out without loading it first: a wide shot fills its frame, a square
 * product shot sits on a backdrop matching its own edges (black for Apple's studio shots,
 * white for a retailer's cut-outs).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalPhoto {

    public static final String DARK = "DARK";
    public static final String LIGHT = "LIGHT";

    private String url;
    private Integer width;
    private Integer height;

    /** DARK | LIGHT — the colour of the photo's edges; null when mixed or unreadable. */
    private String tone;
}
