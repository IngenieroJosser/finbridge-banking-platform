package com.finbridge.paymenthub.shared;

import org.springframework.http.HttpStatus;

public class DomainProblem extends RuntimeException {
    private final HttpStatus status;
    public DomainProblem(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
    public HttpStatus status() { return status; }
    public static DomainProblem notFound(String what) {
        return new DomainProblem(HttpStatus.NOT_FOUND, what + " no existe");
    }
    public static DomainProblem conflict(String message) {
        return new DomainProblem(HttpStatus.CONFLICT, message);
    }
    public static DomainProblem unprocessable(String message) {
        return new DomainProblem(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
    public static DomainProblem forbidden() {
        return new DomainProblem(HttpStatus.FORBIDDEN, "Acceso no autorizado");
    }
    public static DomainProblem badRequest(String message) {
        return new DomainProblem(HttpStatus.BAD_REQUEST, message);
    }
}
