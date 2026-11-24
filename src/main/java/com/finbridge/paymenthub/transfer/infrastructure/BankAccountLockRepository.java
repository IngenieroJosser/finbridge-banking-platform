package com.finbridge.paymenthub.transfer.infrastructure;

import com.finbridge.paymenthub.shared.DomainProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Lee y bloquea filas de account para que estado/titular no cambien antes del commit. */
@Repository
public class BankAccountLockRepository {
    private final JdbcTemplate jdbc;

    public BankAccountLockRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public LockedAccount lock(UUID id) {
        List<LockedAccount> results = jdbc.query("""
                SELECT id, customer_id, currency, status, account_type
                FROM account WHERE id = ? FOR SHARE
                """, (rs, row) -> new LockedAccount(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("customer_id"),
                rs.getString("currency"), rs.getString("status"), rs.getString("account_type")), id);
        if (results.isEmpty()) throw DomainProblem.notFound("Cuenta");
        return results.getFirst();
    }

    public record LockedAccount(UUID id, UUID customerId, String currency,
                                String status, String accountType) { }
}
