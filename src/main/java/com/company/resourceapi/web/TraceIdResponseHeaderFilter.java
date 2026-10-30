package com.company.resourceapi.web;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Returns the request's trace id in the {@value #TRACE_ID_HEADER} response header, so a client can
 * quote it when reporting a problem and it can be found in the logs.
 *
 * <p>Runs after Boot's server observation filter (which has a higher precedence), so the request's
 * span, continued from an incoming {@code traceparent} header if there is one, is already current.
 */
@Component
public class TraceIdResponseHeaderFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final ObjectProvider<Tracer> tracer;

    public TraceIdResponseHeaderFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var currentTracer = tracer.getIfAvailable();
        var span = currentTracer == null ? null : currentTracer.currentSpan();
        if (span != null) {
            // set before the chain runs: the header can't be added once the response is committed
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }
        filterChain.doFilter(request, response);
    }
}
