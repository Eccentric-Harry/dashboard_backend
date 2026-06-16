package com.personal_dashboard.backend;

import com.personal_dashboard.backend.model.DailyTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class TestJackson {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        DailyTask task = new DailyTask();
        // simulate Spring Data instantiation from DB where status is missing but completed is true
        task.setCompleted(true);
        // note: we do NOT set status, simulating missing DB field

        System.out.println("No args constructor JSON:");
        System.out.println(mapper.writeValueAsString(task));
    }
}
