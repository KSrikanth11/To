package com.example.recipient.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Adds a correlation ID and logs request lifecycle without logging request bodies or headers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String suppliedId = request.getHeader("X-Request-ID");
        String requestId = suppliedId != null && SAFE_ID.matcher(suppliedId).matches()
                ? suppliedId : UUID.randomUUID().toString();
        long started = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        log.info("request.start method={} path={}", request.getMethod(), request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            log.warn("request.failed error_type={}", ex.getClass().getSimpleName());
            throw ex;
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            log.info("request.complete status={} duration_ms={}", response.getStatus(), elapsedMs);
            MDC.remove("requestId");
        }
    }
}
