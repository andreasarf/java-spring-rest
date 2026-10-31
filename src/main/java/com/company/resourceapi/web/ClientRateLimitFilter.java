package com.company.resourceapi.web;

import static org.springframework.http.HttpHeaders.RETRY_AFTER;

import com.company.resourceapi.config.RateLimitProperties;
import com.company.resourceapi.services.RateLimiters;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits API requests per client IP address, across all instances (429 + Retry-After). Behind a
 * reverse proxy the address comes from X-Forwarded-For, which Tomcat only trusts from internal proxy
 * addresses ({@code server.forward-headers-strategy=native}), so clients can't pick their own key.
 *
 * <p>Registered for {@code /api/*} by {@link ClientRateLimitConfig}, after the trace id filter, so a
 * refused request still gets its X-Trace-Id.
 */
@RequiredArgsConstructor
@Slf4j
public class ClientRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiters rateLimiters;
    private final RateLimitProperties rateLimits;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var limit = rateLimits.client();
        if (!rateLimiters.tryAcquire("client-rate:" + request.getRemoteAddr(), limit)) {
            log.info("Too many requests from {}", request.getRemoteAddr());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(RETRY_AFTER, String.valueOf(limit.retryAfterSeconds()));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
