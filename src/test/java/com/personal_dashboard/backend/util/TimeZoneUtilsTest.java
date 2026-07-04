package com.personal_dashboard.backend.util;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class TimeZoneUtilsTest {

    @Test
    void testNormalizeTimeZone() {
        assertEquals("UTC", TimeZoneUtils.normalizeTimeZone(null));
        assertEquals("GMT+05:30", TimeZoneUtils.normalizeTimeZone("GMT+5:30"));
        assertEquals("GMT-05:30", TimeZoneUtils.normalizeTimeZone("GMT-5:30"));
        assertEquals("GMT+05:30", TimeZoneUtils.normalizeTimeZone("GMT+05:30"));
        assertEquals("+05:30", TimeZoneUtils.normalizeTimeZone("+5:30"));
        assertEquals("GMT+12:00", TimeZoneUtils.normalizeTimeZone("GMT+12:00"));
        assertEquals("Asia/Kolkata", TimeZoneUtils.normalizeTimeZone("Asia/Kolkata"));
        assertEquals("UTC", TimeZoneUtils.normalizeTimeZone("UTC"));
    }

    @Test
    void testSafeGetZoneId() {
        assertEquals(ZoneId.of("GMT+05:30"), TimeZoneUtils.safeGetZoneId("GMT+5:30"));
        assertEquals(ZoneId.of("GMT-05:30"), TimeZoneUtils.safeGetZoneId("GMT-5:30"));
        assertEquals(ZoneId.of("Asia/Kolkata"), TimeZoneUtils.safeGetZoneId("Asia/Kolkata"));
        assertEquals(ZoneId.of("UTC"), TimeZoneUtils.safeGetZoneId("invalid-timezone-string"));
    }
}
