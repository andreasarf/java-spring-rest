package com.company.resourceapi.web;

import com.company.resourceapi.config.RateLimitProperties;
import com.company.resourceapi.exceptions.RateLimitExceededException;
import com.company.resourceapi.exceptions.RateLimitExceededException.Scope;
import com.company.resourceapi.services.RateLimiters;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Limits API requests per client IP address, across all instances (429 + Retry-After). Behind a
 * reverse proxy the address comes from X-Forwarded-For, which Tomcat only trusts from internal proxy
 * addresses ({@code server.forward-headers-strategy=native}), so clients can't pick their own key.
 *
 * <p>Registered for {@code /api/*} by {@link ClientRateLimitConfig}, after the trace id filter, so a
 * refused request still gets its X-Trace-Id. The refusal is answered like any other
 * {@link RateLimitExceededException}, by the application's exception handling.
 */
@RequiredArgsConstructor
public class ClientRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiters rateLimiters;
    private final RateLimitProperties rateLimits;
    private final HandlerExceptionResolver exceptionResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var limit = rateLimits.client();
        if (!rateLimiters.tryAcquire("client-rate:" + request.getRemoteAddr(), limit)) {
            var refusal = new RateLimitExceededException(Scope.CALLER,
                    "Too many requests from " + request.getRemoteAddr(), limit.retryAfterSeconds());
            exceptionResolver.resolveException(request, response, null, refusal);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
