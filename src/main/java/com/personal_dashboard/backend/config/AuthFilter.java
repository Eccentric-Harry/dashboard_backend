package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    private static final String ALLOWED_ORIGINS =
            "http://localhost:3000,http://localhost:5173,http://localhost:5174,http://127.0.0.1:3000,http://127.0.0.1:5173,https://dashboard-ui-flame-psi.vercel.app,https://harrysdashboard.vercel.app";

    private final AuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();

        if (path.startsWith("/api/v1/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (path.startsWith("/api/v1/")) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                writeCorsError(request, response, "Missing or invalid Authorization header");
                return;
            }

            String token = authHeader.substring(7);
            if (!authService.isValidToken(token)) {
                writeCorsError(request, response, "Invalid or expired token");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeCorsError(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && originMatches(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Allow-Headers", "*");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH");
        }
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"data\":{\"message\":\"" + message + "\"}}");
    }

    private boolean originMatches(String origin) {
        for (String allowed : ALLOWED_ORIGINS.split(",")) {
            if (allowed.equals(origin)) {
                return true;
            }
        }
        return false;
    }
}
