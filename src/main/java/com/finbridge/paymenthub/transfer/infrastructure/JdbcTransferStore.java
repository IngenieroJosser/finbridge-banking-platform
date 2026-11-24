package com.finbridge.paymenthub.transfer.infrastructure;

import com.finbridge.paymenthub.shared.DomainProblem;
import com.finbridge.paymenthub.transfer.api.TransferResponse;
import com.finbridge.paymenthub.transfer.domain.InternalTransferOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** SQL financiero parametrizado; Flyway es propietario del esquema y la BD aplica los postings. */
@Repository
public class JdbcTransferStore {
    private final JdbcTemplate jdbc;

    public JdbcTransferStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(UUID id, InternalTransferOrder order, String correlationId) {
        jdbc.update("""
                INSERT INTO transfer(id, source_id, destination_id, amount, currency,
                                     transfer_type, status, reference, correlation_id)
                VALUES(?, ?, ?, ?, ?, 'INTERNAL', 'COMPLETED', ?, ?)
                """, id, order.source(), order.destination(), order.amount(), order.currency(),
                order.reference(), correlationId);
    }

    public void bookJournal(UUID journalId, UUID transferId, InternalTransferOrder order) {
        jdbc.update("""
                INSERT INTO journal(id, transfer_id, journal_type, currency, status)
                VALUES(?, ?, 'INTERNAL_TRANSFER', 'COP', 'DRAFT')
                """, journalId, transferId);
        // CUSTOMER_LIABILITY: DEBIT reduce saldo origen; CREDIT aumenta destino.
        insertPosting(journalId, order.source(), "DEBIT", order.amount());
        insertPosting(journalId, order.destination(), "CREDIT", order.amount());
        jdbc.update("UPDATE journal SET status='POSTED', posted_at=now() WHERE id=?", journalId);
    }

    private void insertPosting(UUID journal, UUID account, String side, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO posting(id, journal_id, account_id, side, amount, currency)
                VALUES(?, ?, ?, ?, ?, 'COP')
                """, UUID.randomUUID(), journal, account, side, amount);
    }

    public record Owners(UUID source, UUID destination) {}
    public Owners owners(UUID id) {
        var rows = jdbc.query("""
                SELECT s.customer_id AS source_owner, d.customer_id AS destination_owner
                FROM transfer t JOIN account s ON s.id=t.source_id JOIN account d ON d.id=t.destination_id
                WHERE t.id=? AND t.transfer_type='INTERNAL'
                """, (rs, n) -> new Owners(rs.getObject("source_owner", UUID.class),
                rs.getObject("destination_owner", UUID.class)), id);
        if (rows.isEmpty()) throw DomainProblem.notFound("Transferencia");
        return rows.getFirst();
    }

    public TransferResponse find(UUID transferId) {
        List<TransferResponse> found = jdbc.query("""
                SELECT id, transfer_type, status, amount, currency, created_at, correlation_id
                FROM transfer WHERE id=? AND transfer_type='INTERNAL'
                """, (rs, row) -> new TransferResponse(
                (UUID) rs.getObject("id"), rs.getString("transfer_type"),
                rs.getString("status"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("correlation_id")), transferId);
        if (found.isEmpty()) throw DomainProblem.notFound("Transferencia");
        return found.getFirst();
    }
}
