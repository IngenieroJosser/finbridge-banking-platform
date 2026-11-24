package com.finbridge.paymenthub.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finbridge.paymenthub.shared.DomainProblem;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Authenticate the raw body before parsing it. Never log credentials or provider payload. */
@Service
public class ProviderCallbacks {
    private final byte[] secret;
    private final ObjectMapper json;
    private final ExternalPaymentService payments;
    private final MeterRegistry metrics;
    public ProviderCallbacks(@Value("${finbridge.provider.hmac-secret:}") String secret,ObjectMapper json,ExternalPaymentService payments,MeterRegistry metrics) {
        this.secret=secret.getBytes(StandardCharsets.UTF_8); this.json=json;this.payments=payments;this.metrics=metrics;
    }
    public boolean configured(){return secret.length>=32;}
    public String accept(String timestamp,String signature,byte[] raw) {
        if(raw==null || raw.length>16384) throw DomainProblem.badRequest("Callback demasiado grande");
        if(!configured() || timestamp==null || !timestamp.matches("[0-9]{1,12}") || signature==null || !signature.matches("[0-9a-fA-F]{64}")) throw unauthorized();
        long ts=Long.parseLong(timestamp), now=Instant.now().getEpochSecond();
        if(ts<now-300 || ts>now+300 || !MessageDigest.isEqual(HexFormat.of().parseHex(signature),HexFormat.of().parseHex(sign(timestamp,raw)))) throw unauthorized();
        ExternalPaymentService.ProviderCallback event;
        try {event=json.readerFor(ExternalPaymentService.ProviderCallback.class).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(raw);} catch(Exception e){throw DomainProblem.badRequest("Evento inválido");}
        if(event==null || !ExternalPaymentService.safe(event.eventId(),1,128) || !ExternalPaymentService.safe(event.providerTransferId(),1,128) || event.transferId()==null || event.occurredAt()==null || event.status()==null || !event.status().matches("[A-Z_]{1,20}")) throw DomainProblem.badRequest("Evento inválido");
        String result=payments.apply(event,ExternalPaymentService.digest(raw));
        metrics.counter("finbridge.provider.callbacks","outcome",result).increment();
        org.slf4j.LoggerFactory.getLogger(getClass()).atInfo().addKeyValue("event","provider_callback").addKeyValue("transferId",event.transferId().toString()).addKeyValue("outcome",result).log("Authenticated callback processed");
        return result;
    }
    public String sign(String timestamp,byte[] raw) {
        if(!configured()) throw new IllegalStateException("Provider HMAC secret not configured");
        try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));mac.update((timestamp+".").getBytes(StandardCharsets.US_ASCII));return HexFormat.of().formatHex(mac.doFinal(raw));}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    private DomainProblem unauthorized(){metrics.counter("finbridge.provider.callbacks","outcome","UNAUTHORIZED").increment();return new DomainProblem(HttpStatus.UNAUTHORIZED,"Firma o timestamp inválido");}
}
