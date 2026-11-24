package com.finbridge.paymenthub.shared;

import org.slf4j.MDC;
import java.util.UUID;

public final class RequestContext {
    private RequestContext() {}
    public static String correlationId(String header) {
        String current = MDC.get("correlationId");
        if (current != null) return current;
        return header != null && valid(header) ? header : UUID.randomUUID().toString();
    }
    static boolean valid(String header) { return header.matches("[A-Za-z0-9._-]{1,128}"); }
}
