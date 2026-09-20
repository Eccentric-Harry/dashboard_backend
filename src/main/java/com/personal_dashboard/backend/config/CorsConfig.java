package com.personal_dashboard.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Slf4j
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * Note: entries are matched against the browser's Origin header, which never
     * carries a path or a trailing slash — the entries below that end in "/" can
     * never match anything.
     */
    private static final String[] ALLOWED_ORIGINS = {
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
            "https://dashboard-ui-eccentric-harry-prod.vercel.app/"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        log.info("Registering CORS mapping for /api/** (allowCredentials=true)");
        registry.addMapping("/api/**")
                .allowedOrigins(ALLOWED_ORIGINS)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);

        // KeepAliveController's /ping sits outside /api, so it was not covered by the
        // mapping above and the browser could not read it cross-origin: the frontend's
        // liveness probe failed for want of an Access-Control-Allow-Origin header while
        // every /api/v1 call from the same page succeeded. No credentials — it is an
        // unauthenticated liveness check and nothing about it is per-user.
        log.info("Registering CORS mapping for /ping (liveness probe, no credentials)");
        registry.addMapping("/ping")
                .allowedOrigins(ALLOWED_ORIGINS)
                .allowedMethods("GET", "OPTIONS")
                .maxAge(3600);
    }
}
