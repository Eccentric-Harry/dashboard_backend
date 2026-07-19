package com.personal_dashboard.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Slf4j
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        log.info("Registering CORS mapping for /api/** (allowCredentials=true)");
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:3000",
                        "http://localhost:5173",
                        "http://localhost:5174",
                        "http://127.0.0.1:3000",
                        "http://127.0.0.1:5173",
                        "https://dashboard-ui-flame-psi.vercel.app",
                        "https://dashboard-6ni3iac5z-eccentric-harry-prod.vercel.app",
                        "https://dashboard-lzenpw83a-eccentric-harry-prod.vercel.app/",
                        "https://dashboard-ui-git-release-100-eccentric-harry-prod.vercel.app/",
                        "https://harrysdashboard.vercel.app",
                        "https://dashboard-ui-eccentric-harry-prod.vercel.app/")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
