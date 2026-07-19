package com.personal_dashboard.backend.util;

import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;

@Slf4j
public class TimeZoneUtils {

    /**
     * Normalizes a timezone string (e.g. GMT+5:30 -> GMT+05:30) so that ZoneId.of() can parse it successfully.
     */
    public static String normalizeTimeZone(String tz) {
        if (tz == null) {
            return "UTC";
        }
        int signIdx = tz.lastIndexOf('+');
        if (signIdx == -1) {
            signIdx = tz.lastIndexOf('-');
        }
        if (signIdx != -1) {
            int colonIdx = tz.indexOf(':', signIdx);
            if (colonIdx != -1) {
                int digits = colonIdx - signIdx - 1;
                if (digits == 1) {
                    return tz.substring(0, signIdx + 1) + "0" + tz.substring(signIdx + 1);
                }
            }
        }
        return tz;
    }

    /**
     * Safely parses a timezone string into ZoneId, falling back to UTC if invalid.
     */
    public static ZoneId safeGetZoneId(String tz) {
        try {
            return ZoneId.of(normalizeTimeZone(tz));
        } catch (Exception e) {
            log.warn("Invalid timezone '{}', falling back to UTC: {}", tz, e.getMessage());
            return ZoneId.of("UTC");
        }
    }
}
