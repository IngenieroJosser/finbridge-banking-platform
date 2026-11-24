package com.finbridge.paymenthub.security;

import com.finbridge.paymenthub.shared.ProblemResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.http.HttpStatus;
import java.util.*;

@Configuration
public class SecurityConfig {
    private static final Set<String> ROLES = Set.of("CUSTOMER", "OPERATIONS", "AUDITOR", "ADMIN");

    @Bean
    JwtDecoder jwtDecoder(@Value("${finbridge.security.issuer}") String issuer,
                          @Value("${finbridge.security.jwk-set-uri}") String jwks,
                          @Value("${finbridge.security.audience}") String audience) {
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank())
            throw new IllegalStateException("JWT issuer, JWKS and audience are required");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getAudience() != null
                    && jwt.getAudience().contains(audience) && validSubject(jwt.getSubject());
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required claims invalid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    static boolean validSubject(String subject) {
        try { return subject != null && UUID.fromString(subject).toString().equals(subject); }
        catch (IllegalArgumentException e) { return false; }
    }

    static JwtAuthenticationToken authentication(Jwt jwt) {
        Object claim = jwt.getClaims().get("roles");
        var authorities = claim instanceof Collection<?> roles ? roles.stream()
                .filter(String.class::isInstance).map(String.class::cast).filter(ROLES::contains)
                .distinct().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()
                : List.<SimpleGrantedAuthority>of();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, ProblemResponses problems) throws Exception {
        var unauthorized = new BearerTokenAuthenticationEntryPoint();
        var forbidden = new BearerTokenAccessDeniedHandler();
        org.springframework.security.web.AuthenticationEntryPoint entry = (req, res, ex) -> {
            unauthorized.commence(req, res, ex);
            // Never return decoder diagnostics or token contents.
            res.setHeader("WWW-Authenticate", "Bearer");
            problems.write(req, res, HttpStatus.UNAUTHORIZED, "Autenticación requerida o token inválido");
        };
        org.springframework.security.web.access.AccessDeniedHandler denied = (req, res, ex) -> {
            forbidden.handle(req, res, ex);
            res.setHeader("WWW-Authenticate", "Bearer");
            problems.write(req, res, HttpStatus.FORBIDDEN, "Acceso no autorizado");
        };
        return http.csrf(csrf -> csrf.disable()) // Bearer header only; no cookie or session authentication.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").hasAnyRole("OPERATIONS", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*/opening-balance").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts/*", "/api/v1/accounts/*/balance", "/api/v1/transfers/*").hasAnyRole("CUSTOMER", "OPERATIONS", "AUDITOR", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/transfers/*").hasAnyRole("AUDITOR", "OPERATIONS")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(entry).accessDeniedHandler(denied))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(SecurityConfig::authentication))
                        .authenticationEntryPoint(entry).accessDeniedHandler(denied)).build();
    }
}
