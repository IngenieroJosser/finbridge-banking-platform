package com.finbridge.paymenthub.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Independent commit models a provider that can accept an event before the bank records the acknowledgement. */
@Service
public class SimulatedProvider {
    private final JdbcTemplate db;
    public SimulatedProvider(JdbcTemplate db){this.db=db;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public String submit(UUID event,ExternalPaymentService.Payment payment) {
        // Deterministic fault injection, synthetic only; no real network or banking credentials.
        if("SANDBOX_TIMEOUT".equals(payment.bankCode())) throw new Unavailable();
        String providerId=ExternalPaymentService.providerId(payment.id());
        db.update("INSERT INTO simulated_provider_receipt(event_id,transfer_id,provider_transfer_id,result) VALUES(?,?,?,?) ON CONFLICT(event_id) DO NOTHING",event,payment.id(),providerId,"SANDBOX_REJECT".equals(payment.bankCode())?"REJECTED":"SETTLED");
        return db.queryForObject("SELECT provider_transfer_id FROM simulated_provider_receipt WHERE event_id=? AND transfer_id=?",String.class,event,payment.id());
    }
    public static class Unavailable extends RuntimeException {}
    public record Receipt(UUID event,UUID transfer,String providerId,String result,OffsetDateTime time) {}
}
