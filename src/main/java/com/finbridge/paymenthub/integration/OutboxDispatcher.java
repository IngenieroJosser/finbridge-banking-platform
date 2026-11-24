package com.finbridge.paymenthub.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** At-least-once delivery. SKIP LOCKED allows concurrent workers; provider deduplicates durable event IDs. */
@Service
public class OutboxDispatcher {
    private final JdbcTemplate db;
    private final ExternalPaymentService payments;
    private final SimulatedProvider provider;
    private final ProviderCallbacks callbacks;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    public OutboxDispatcher(JdbcTemplate db,ExternalPaymentService payments,SimulatedProvider provider,ProviderCallbacks callbacks,ObjectMapper json,MeterRegistry metrics){this.db=db;this.payments=payments;this.provider=provider;this.callbacks=callbacks;this.json=json;this.metrics=metrics;}
    private record Event(UUID id,UUID transfer,int attempts){}
    @Transactional
    public boolean dispatchOne(boolean crashAfterPublish) {
        var rows=db.query("""
            SELECT id,aggregate_id,attempts FROM outbox_event WHERE status='PENDING' AND event_type='TransferRequested' AND next_attempt_at<=now()
            ORDER BY next_attempt_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """,(r,n)->new Event(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3)));
        if(rows.isEmpty()) return false;
        Event event=rows.getFirst(); var payment=payments.lockPayment(event.transfer());
        if(Set.of("SETTLED","REJECTED").contains(payment.status())) {markPublished(event.id());return true;}
        String providerId;
        try {providerId=provider.submit(event.id(),payment);} catch(SimulatedProvider.Unavailable ex) {
            int attempts=event.attempts()+1; boolean dead=attempts>=5;
            db.update("UPDATE outbox_event SET attempts=?,status=?,last_error='PROVIDER_UNAVAILABLE',next_attempt_at=now()+CAST(? AS integer)*interval '1 second' WHERE id=?",attempts,dead?"FAILED":"PENDING",Math.min(60,1<<attempts),event.id());
            if(dead) payments.review(payment);
            metrics.counter("finbridge.outbox.delivery","outcome",dead?"DLQ":"RETRY").increment();return true;
        }
        // Fault injection: provider transaction is committed, local ack and attempts are rolled back.
        if(crashAfterPublish) throw new SimulatedCrash();
        payments.acknowledge(payment,providerId); markPublished(event.id());
        metrics.counter("finbridge.outbox.delivery","outcome","PUBLISHED").increment();return true;
    }
    private void markPublished(UUID id){db.update("UPDATE outbox_event SET status='PUBLISHED',attempts=attempts+1,published_at=now(),last_error=NULL WHERE id=?",id);}
    /** Separate callback delivery. Mark delivered only after the authenticated financial transaction commits. */
    public boolean deliverOne() {
        var rows=db.query("""
            SELECT event_id,transfer_id,provider_transfer_id,result,occurred_at FROM simulated_provider_receipt
            WHERE delivered_at IS NULL AND callback_attempts<5 AND callback_due_at<=now() ORDER BY callback_due_at,event_id LIMIT 1
            """,(r,n)->new SimulatedProvider.Receipt(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getObject(5,java.time.OffsetDateTime.class)));
        if(rows.isEmpty()) return false;
        var r=rows.getFirst();
        try {
            byte[] raw=json.writeValueAsBytes(new ExternalPaymentService.ProviderCallback("evt-"+r.event(),r.providerId(),r.transfer(),r.result(),r.time()));
            String ts=Long.toString(Instant.now().getEpochSecond());callbacks.accept(ts,callbacks.sign(ts,raw),raw);
            db.update("UPDATE simulated_provider_receipt SET delivered_at=now() WHERE event_id=? AND delivered_at IS NULL",r.event());
        } catch(Exception ex) {
            db.update("UPDATE simulated_provider_receipt SET callback_attempts=callback_attempts+1,callback_due_at=now()+interval '30 seconds' WHERE event_id=? AND delivered_at IS NULL",r.event());
            metrics.counter("finbridge.provider.callback.delivery","outcome","RETRY").increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Simulated callback delivery failed; retained for retry");
        }
        return true;
    }
    public static class SimulatedCrash extends RuntimeException {}
}
