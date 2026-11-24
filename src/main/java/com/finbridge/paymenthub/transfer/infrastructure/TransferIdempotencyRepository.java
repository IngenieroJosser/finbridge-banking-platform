package com.finbridge.paymenthub.transfer.infrastructure;

import com.finbridge.paymenthub.shared.DomainProblem;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Advisory lock transaccional + PK(actor_id, operation, idempotency_key) como garantía durable. */
@Repository
public class TransferIdempotencyRepository {
    private final JdbcTemplate jdbc;

    public TransferIdempotencyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock(UUID actorId, String operation, String key) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, actorId + "|" + operation + "|" + key);
                statement.execute();
            }
            return null;
        });
    }

    public Optional<StoredRequest> find(UUID actorId, String operation, String key) {
        List<StoredRequest> records = jdbc.query("""
                SELECT request_hash, resource_id FROM idempotency_record
                WHERE actor_id=? AND operation=? AND idempotency_key=?
                """, (rs, row) -> new StoredRequest(rs.getString("request_hash"),
                (UUID) rs.getObject("resource_id")), actorId, operation, key);
        return records.stream().findFirst();
    }

    public void save(UUID actorId, String operation, String key, String requestHash, UUID transferId) {
        jdbc.update("""
                INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id)
                VALUES(?,?,?,?,?)
                """, actorId, operation, key, requestHash, transferId);
    }

    public static void validateKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128 ||
                key.chars().anyMatch(c -> c < 33 || c > 126))
            throw DomainProblem.badRequest("Idempotency-Key debe contener 16-128 caracteres ASCII imprimibles, sin espacios");
    }

    public record StoredRequest(String requestHash, UUID resourceId) { }
}
