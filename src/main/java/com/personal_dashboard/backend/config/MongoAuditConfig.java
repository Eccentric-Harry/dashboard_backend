package com.personal_dashboard.backend.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@Slf4j
@Configuration
@EnableMongoAuditing
public class MongoAuditConfig {

    @PostConstruct
    void logAuditingEnabled() {
        log.info("MongoDB auditing enabled (createdDate/lastModifiedDate fields)");
    }
}
