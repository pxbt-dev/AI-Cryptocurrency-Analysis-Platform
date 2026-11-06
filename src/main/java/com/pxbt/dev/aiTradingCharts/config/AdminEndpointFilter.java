package com.pxbt.dev.aiTradingCharts.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protects operator-only endpoints (manual training, manual backtest, JVM debug info).
 *
 * Callers must send the header {@code X-Admin-Token} matching the ADMIN_TOKEN
 * environment variable. If ADMIN_TOKEN is not set, these endpoints are switched off
 * entirely (404), so a fresh deploy is closed by default.
 *
 * The public chart UI never calls these paths.
 */
@Slf4j
@Component
public class AdminEndpointFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Token";

    private final String adminToken;

    public AdminEndpointFilter(@Value("${ADMIN_TOKEN:}") String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
        if (this.adminToken.isEmpty()) {
            log.info("🔒 ADMIN_TOKEN not set - admin endpoints are disabled");
        }
    }

    static boolean isAdminPath(String path) {
        return path.equals("/api/system/train")
                || path.startsWith("/api/debug/")
                || path.equals("/api/backtest/run");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isAdminPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (adminToken.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String supplied = request.getHeader(HEADER);
        if (supplied == null || !MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), adminToken.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
