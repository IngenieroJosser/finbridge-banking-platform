package com.finbridge.paymenthub.integration;

import io.micrometer.core.instrument.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class IntegrationMetrics {
    private final JdbcTemplate db; private final MeterRegistry registry;
    public IntegrationMetrics(JdbcTemplate db,MeterRegistry registry){this.db=db;this.registry=registry;}
    @EventListener(ApplicationReadyEvent.class)
    public void register(){
        for(String status:List.of("RESERVED","SUBMITTED","IN_REVIEW","SETTLED","REJECTED"))
            Gauge.builder("finbridge.transfers",db,d->d.queryForObject("SELECT count(*) FROM transfer WHERE transfer_type='EXTERNAL' AND status=?",Long.class,status)).tag("status",status).register(registry);
        Gauge.builder("finbridge.transfer.accepted",db,d->d.queryForObject("SELECT count(*) FROM transfer",Long.class)).register(registry);
        Gauge.builder("finbridge.transfer.rejected",db,d->d.queryForObject("SELECT count(*) FROM transfer WHERE status='REJECTED'",Long.class)).register(registry);
        Gauge.builder("finbridge.provider.callback.dlq",db,d->d.queryForObject("SELECT count(*) FROM simulated_provider_receipt WHERE delivered_at IS NULL AND callback_attempts>=5",Long.class)).register(registry);
        Gauge.builder("finbridge.http.error.ratio",registry,r->{long all=0,errors=0;for(var t:r.find("http.server.requests").timers()){String uri=t.getId().getTag("uri"); if(uri==null||!uri.startsWith("/api/"))continue; all+=t.count();String st=t.getId().getTag("status");if(st!=null&&st.startsWith("5"))errors+=t.count();}return all==0?0.0:(double)errors/all;}).register(registry);
        Gauge.builder("finbridge.outbox.pending",db,d->d.queryForObject("SELECT count(*) FROM outbox_event WHERE status='PENDING'",Long.class)).register(registry);
        Gauge.builder("finbridge.outbox.dlq",db,d->d.queryForObject("SELECT count(*) FROM outbox_event WHERE status='FAILED'",Long.class)).register(registry);
        Gauge.builder("finbridge.outbox.oldest.age.seconds",db,d->d.queryForObject("SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM outbox_event WHERE status='PENDING'",Double.class)).baseUnit("seconds").register(registry);
        Gauge.builder("finbridge.reconciliation.mismatches",db,d->d.queryForObject("SELECT coalesce(sum(mismatched_count),0) FROM reconciliation_batch",Long.class)).register(registry);
        Gauge.builder("finbridge.ledger.invariant.failure",db,d->d.queryForObject("SELECT count(*) FROM (SELECT journal_id FROM posting GROUP BY journal_id HAVING sum(CASE WHEN side='DEBIT' THEN amount ELSE -amount END)<>0) q",Long.class)).register(registry);
    }
}
