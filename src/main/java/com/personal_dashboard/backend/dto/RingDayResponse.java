package com.personal_dashboard.backend.dto;

import com.personal_dashboard.backend.model.DailyRing;
import com.personal_dashboard.backend.model.StreakState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything the Non-Negotiables hero needs in one read: today's three rings,
 * the streak/freeze state, and the level math pre-computed so the client never
 * re-derives it (xpIntoLevel / xpForNextLevel are relative to the current level).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RingDayResponse {

    private DailyRing ring;

    private StreakState streak;

    private long xpIntoLevel;

    private long xpForNextLevel;
}
