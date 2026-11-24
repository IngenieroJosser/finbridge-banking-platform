package com.finbridge.paymenthub.shared;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);
    private final ProblemResponses problems;
    public CorrelationFilter(ProblemResponses problems) { this.problems = problems; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String supplied = req.getHeader("X-Correlation-ID");
        boolean valid = supplied == null || RequestContext.valid(supplied);
        String id = supplied != null && valid ? supplied : UUID.randomUUID().toString();
        long start = System.nanoTime();
        try (var ignored = MDC.putCloseable("correlationId", id)) {
            res.setHeader("X-Correlation-ID", id);
            if (!valid) { problems.write(req, res, HttpStatus.BAD_REQUEST, "X-Correlation-ID inválido"); return; }
            chain.doFilter(req, res);
        } finally {
            try (var ignored = MDC.putCloseable("correlationId", id)) {
                Object route = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                log.atInfo().addKeyValue("event", "http_request").addKeyValue("method", req.getMethod())
                        .addKeyValue("route", route == null ? "unmatched" : route.toString())
                        .addKeyValue("status", res.getStatus())
                        .addKeyValue("durationMs", (System.nanoTime()-start)/1_000_000).log("HTTP request finished");
            }
        }
    }
}
