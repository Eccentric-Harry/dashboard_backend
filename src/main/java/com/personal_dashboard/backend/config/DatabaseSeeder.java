package com.personal_dashboard.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DatabaseSeeder implements CommandLineRunner {

    @Override
    public void run(String... args) {
        log.info("DatabaseSeeder: data already present in MongoDB Atlas — skipping seed.");
    }
}
