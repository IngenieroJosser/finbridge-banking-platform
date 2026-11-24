package com.finbridge.paymenthub.security;

import com.finbridge.paymenthub.shared.DomainProblem;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.UUID;

/** Identity exclusively from the validated access token. One trusted issuer, canonical UUID subject. */
public final class CurrentActor {
    private CurrentActor() {}
    private static JwtAuthenticationToken token() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)
                || !jwt.isAuthenticated()) throw DomainProblem.forbidden();
        return jwt;
    }
    public static UUID id() { return UUID.fromString(token().getToken().getSubject()); }
    public static boolean has(String role) {
        return token().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
    public static void require(String... roles) {
        for (String role : roles) if (has(role)) return;
        throw DomainProblem.forbidden();
    }
    public static void requireOwner(UUID customerId) {
        if (!id().equals(customerId)) throw DomainProblem.forbidden();
    }
    public static void readAccount(UUID customerId) {
        if (has("OPERATIONS") || has("AUDITOR") || has("ADMIN")) return;
        require("CUSTOMER"); requireOwner(customerId);
    }
}
