package com.finbridge.paymenthub.shared;

import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.security.access.AccessDeniedException;

@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {
    private final ProblemResponses problems;
    public ProblemHandler(ProblemResponses problems) { this.problems = problems; }
    @ExceptionHandler(DomainProblem.class)
    public ResponseEntity<ProblemDetail> domain(DomainProblem e) { return response(e.status(), e.getMessage()); }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> denied(AccessDeniedException e) { return response(HttpStatus.FORBIDDEN, "Acceso no autorizado"); }
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> invalid(Exception e) { return response(HttpStatus.BAD_REQUEST, "Entrada inválida"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e) {
        // Do not serialize exception messages/SQL/parameters. Correlation is in MDC.
        LoggerFactory.getLogger(getClass()).atError().addKeyValue("event", "unexpected_error")
                .addKeyValue("exceptionType", e.getClass().getSimpleName()).log("Request failed");
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servicio");
    }
    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpHeaders safe = new HttpHeaders(); safe.putAll(headers);
        safe.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String detail = status.is5xxServerError() ? "Error interno del servicio" : "Solicitud inválida o no soportada";
        return new ResponseEntity<>(problems.create(status, detail), safe, status);
    }
    private ResponseEntity<ProblemDetail> response(HttpStatus status, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.create(status, detail));
    }
}
