package com.finbridge.paymenthub.audit;

import com.finbridge.paymenthub.security.CurrentActor;
import com.finbridge.paymenthub.shared.DomainProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read-only operational evidence, with no free-text reference or customer PII. */
@Service
public class TransferAuditService {
    private final JdbcTemplate jdbc;
    public TransferAuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Event(UUID id, UUID actorId, String eventType, String correlationId, OffsetDateTime occurredAt) {}
    public record Posting(UUID id, UUID accountId, String side, BigDecimal amount, String currency, UUID journalId) {}
    public record Evidence(UUID transferId, String correlationId, String status, UUID journalId,
                           List<Event> events, List<Posting> postings, List<UUID> journalIds) {}
    private record Header(String correlationId, String status, UUID journalId) {}
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Evidence find(UUID id) {
        CurrentActor.require("AUDITOR", "OPERATIONS");
        var header = jdbc.query("""
                SELECT t.correlation_id,t.status,j.id AS journal_id FROM transfer t
                LEFT JOIN journal j ON j.transfer_id=t.id AND j.journal_type IN ('INTERNAL_TRANSFER','EXTERNAL_RESERVE') WHERE t.id=?
                """, (rs,n) -> new Header(rs.getString("correlation_id"),rs.getString("status"),rs.getObject("journal_id",UUID.class)), id);
        if (header.isEmpty()) throw DomainProblem.notFound("Transferencia");
        var h = header.getFirst();
        var events = jdbc.query("""
                SELECT id,actor_id,event_type,correlation_id,occurred_at FROM audit_event
                WHERE aggregate_type='TRANSFER' AND aggregate_id=? ORDER BY occurred_at,id
                """, (rs,n) -> new Event(rs.getObject("id",UUID.class),rs.getObject("actor_id",UUID.class),
                rs.getString("event_type"),rs.getString("correlation_id"),rs.getObject("occurred_at",OffsetDateTime.class)),id);
        List<Posting> postings = h.journalId()==null ? List.of() : jdbc.query("""
                SELECT p.id,p.account_id,p.side,p.amount,p.currency,p.journal_id FROM posting p JOIN journal j ON j.id=p.journal_id WHERE j.transfer_id=? ORDER BY j.created_at,p.id
                """, (rs,n) -> new Posting(rs.getObject("id",UUID.class),rs.getObject("account_id",UUID.class),
                rs.getString("side"),rs.getBigDecimal("amount"),rs.getString("currency"),rs.getObject("journal_id",UUID.class)),id);
        var journals=jdbc.query("SELECT id FROM journal WHERE transfer_id=? ORDER BY created_at,id",(r,n)->r.getObject(1,UUID.class),id);
        return new Evidence(id,h.correlationId(),h.status(),h.journalId(),events,postings,journals);
    }
}
