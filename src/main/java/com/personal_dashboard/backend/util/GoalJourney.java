package com.personal_dashboard.backend.util;

import com.personal_dashboard.backend.dto.GoalJourneyResponse.JourneyDay;
import com.personal_dashboard.backend.model.GoalCheckIn;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A goal's check-ins as a journey: one entry per day that had any, oldest first. Pure, so
 * guest mode ports it line for line (mocks/guest-goals.ts). A day without progress is
 * simply absent — a journey never records a gap.
 */
public final class GoalJourney {

    private GoalJourney() {
    }

    public static List<JourneyDay> days(List<GoalCheckIn> checkIns, LocalDate today) {
        Map<LocalDate, List<GoalCheckIn>> byDay = new TreeMap<>();
        for (GoalCheckIn c : checkIns) {
            if (c.getDate() != null && !c.getDate().isAfter(today) && c.getValue() > 0) {
                byDay.computeIfAbsent(c.getDate(), d -> new ArrayList<>()).add(c);
            }
        }
        List<JourneyDay> out = new ArrayList<>(byDay.size());
        byDay.forEach((day, list) -> {
            list.sort(Comparator.comparing(GoalCheckIn::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            LinkedHashSet<String> practices = new LinkedHashSet<>();
            List<String> notes = new ArrayList<>();
            List<String> sessions = new ArrayList<>();
            double value = 0;
            int minutes = 0;
            for (GoalCheckIn c : list) {
                value += c.getValue();
                if (c.getSession() != null) {
                    sessions.add(c.getSession());
                }
                if (c.getMinutes() != null) {
                    minutes += c.getMinutes();
                }
                if (c.getPractice() != null) {
                    practices.add(c.getPractice());
                }
                if (c.getNote() != null && !c.getNote().isBlank()) {
                    notes.add(c.getNote());
                }
            }
            out.add(JourneyDay.builder()
                    .date(day.toString())
                    .entries(list.size())
                    .value(value)
                    .practices(new ArrayList<>(practices))
                    .notes(notes)
                    .sessions(sessions)
                    .minutes(minutes)
                    .build());
        });
        return out;
    }
}
