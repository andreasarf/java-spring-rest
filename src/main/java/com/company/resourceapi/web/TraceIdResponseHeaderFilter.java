package com.company.resourceapi.web;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Returns the request's trace id in the {@value #TRACE_ID_HEADER} response header, so a client can
 * quote it when reporting a problem and it can be found in the logs.
 *
 * <p>Ordered right after Boot's server observation filter ({@code HIGHEST_PRECEDENCE + 1}), so the
 * request's span, continued from an incoming trace header if there is one, is already current, and
 * the header is set before any later filter (e.g. security) can reject the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdResponseHeaderFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final Tracer tracer;

    /** No tracer in web slice tests, which don't load the tracing auto-configuration. */
    public TraceIdResponseHeaderFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var span = tracer.currentSpan();
        // no span, or a no-op one (tracing off): no header rather than an empty one
        if (span != null && !span.isNoop()) {
            // set before the chain runs: the header can't be added once the response is committed
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }
        filterChain.doFilter(request, response);
    }
}
