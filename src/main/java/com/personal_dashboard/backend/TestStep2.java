package com.personal_dashboard.backend;

import com.personal_dashboard.backend.model.DailyTask;
import com.personal_dashboard.backend.repository.DailyTaskRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TestStep2 {
    public static void main(String[] args) {
        SpringApplication.run(TestStep2.class, args);
    }

    @Bean
    public CommandLineRunner run(DailyTaskRepository repo) {
        return args -> {
            for (DailyTask t : repo.findAll()) {
                if ("Testing /tasks UI".equals(t.getTitle())) {
                    System.out.println("Spring Data Loaded Status: " + t.getStatus());
                    System.out.println("Spring Data Loaded Completed: " + t.getCompleted());
                }
            }
            System.exit(0);
        };
    }
}
