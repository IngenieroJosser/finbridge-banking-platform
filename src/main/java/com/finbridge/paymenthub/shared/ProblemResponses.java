package com.finbridge.paymenthub.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class ProblemResponses {
    private final ObjectMapper mapper;
    public ProblemResponses(ObjectMapper mapper) { this.mapper = mapper; }
    public ProblemDetail create(HttpStatusCode status, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("correlationId", RequestContext.correlationId(null));
        return p;
    }
    public void write(HttpServletRequest req, HttpServletResponse res, HttpStatus status, String detail) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), create(status, detail));
    }
}
