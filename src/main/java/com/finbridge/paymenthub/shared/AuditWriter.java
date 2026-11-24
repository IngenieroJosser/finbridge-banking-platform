package com.finbridge.paymenthub.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class AuditWriter {
    private final JdbcTemplate jdbc;
    public AuditWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void append(String aggregateType, UUID aggregateId, UUID actorId,
                       String eventType, String correlationId, String detail) {
        jdbc.update("""
                INSERT INTO audit_event(id,aggregate_type,aggregate_id,actor_id,event_type,correlation_id,metadata)
                VALUES(?,?,?,?,?,?,jsonb_build_object('description',CAST(? AS TEXT)))
                """, UUID.randomUUID(), aggregateType, aggregateId, actorId, eventType,
                correlationId, detail);
    }
}
