package com.finbridge.paymenthub.ledger.application;

import com.finbridge.paymenthub.account.domain.*;
import com.finbridge.paymenthub.account.infrastructure.*;
import com.finbridge.paymenthub.ledger.api.*;
import com.finbridge.paymenthub.shared.*;
import com.finbridge.paymenthub.security.CurrentActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

@Service
public class OpeningFundingService {
    private static final UUID BANK_CLEARING_ACCOUNT =
            UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final String OPERATION = "OPENING_FUNDING";
    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final JdbcTemplate jdbc;
    private final AuditWriter audit;

    public OpeningFundingService(AccountRepository accounts, AccountBalanceRepository balances,
                                 JdbcTemplate jdbc, AuditWriter audit) {
        this.accounts = accounts;
        this.balances = balances;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public FundingResponse fund(UUID customerAccountId, String key,
                                OpeningBalanceRequest request, String correlationHeader) {
        CurrentActor.require("ADMIN");
        if (key == null || key.length() < 16 || key.length() > 128 || key.isBlank())
            throw DomainProblem.badRequest("Idempotency-Key debe tener 16-128 caracteres");
        BigDecimal amount = request.amount().setScale(2); // La validación @Digits ya rechazó >2 decimales.
        String reason = request.reason().trim();
        String correlationId = RequestContext.correlationId(correlationHeader);
        String requestHash = sha256(customerAccountId + "|" + amount.toPlainString() + "|COP|" + reason);
        UUID actor = CurrentActor.id();

        // Serialización transaccional de la misma clave: PostgreSQL advisory xact lock.
        // La PK de idempotency_record sigue siendo la garantía durable de unicidad.
        advisoryLock(actor + "|" + OPERATION + "|" + key);
        List<ExistingKey> existing = jdbc.query("""
                SELECT request_hash, resource_id FROM idempotency_record
                WHERE actor_id=? AND operation=? AND idempotency_key=?
                """, (rs, row) -> new ExistingKey(rs.getString("request_hash"),
                (UUID) rs.getObject("resource_id")), actor, OPERATION, key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().requestHash().equals(requestHash))
                throw DomainProblem.conflict("Idempotency-Key reutilizada con otro contenido");
            return new FundingResponse(existing.getFirst().journalId(), customerAccountId, amount, "COP");
        }

        AccountEntity customer = accounts.findById(customerAccountId)
                .orElseThrow(() -> DomainProblem.notFound("Cuenta"));
        if (customer.getAccountType() != AccountType.CUSTOMER_LIABILITY)
            throw DomainProblem.unprocessable("Solo se permite fondeo inicial a cuentas cliente");
        if (customer.getStatus() != AccountStatus.ACTIVE)
            throw DomainProblem.unprocessable("Cuenta no activa");
        AccountEntity bank = accounts.findById(BANK_CLEARING_ACCOUNT)
                .orElseThrow(() -> DomainProblem.notFound("Contrapartida del banco"));
        if (bank.getAccountType() != AccountType.BANK_ASSET || bank.getStatus() != AccountStatus.ACTIVE)
            throw DomainProblem.unprocessable("Contrapartida bancaria inválida");

        // Orden de adquisición idéntico para todos los flujos que escriban estas cuentas.
        List<UUID> ordered = new ArrayList<>(List.of(customerAccountId, BANK_CLEARING_ACCOUNT));
        ordered.sort(UUID::compareTo);
        Map<UUID, AccountBalanceEntity> locked = new HashMap<>();
        for (UUID id : ordered) {
            locked.put(id, balances.lockById(id)
                    .orElseThrow(() -> DomainProblem.notFound("Proyección contable")));
        }
        if (locked.get(customerAccountId).getAvailableAmount().compareTo(BigDecimal.ZERO) != 0)
            throw DomainProblem.conflict("La cuenta ya tiene saldo; apertura permitida una única vez");
        if (jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM journal
                              WHERE opening_account_id=? AND journal_type='OPENING_FUNDING')
                """, Boolean.class, customerAccountId))
            throw DomainProblem.conflict("El fondeo inicial ya fue aplicado a esta cuenta");

        UUID journalId = UUID.randomUUID();
        String businessKey = sha256(actor + "|" + OPERATION + "|" + key);
        jdbc.update("""
                INSERT INTO journal(id, opening_account_id, journal_type, currency, status, business_key)
                VALUES(?, ?, 'OPENING_FUNDING', 'COP', 'DRAFT', ?)
                """, journalId, customerAccountId, businessKey);
        // Dr Banco (activo aumenta). Cr Cliente (pasivo aumenta). Dos partidas equivalentes.
        insertPosting(journalId, BANK_CLEARING_ACCOUNT, "DEBIT", amount);
        insertPosting(journalId, customerAccountId, "CREDIT", amount);
        jdbc.update("UPDATE journal SET status='POSTED', posted_at=now() WHERE id=?", journalId);
        jdbc.update("""
                INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id)
                VALUES(?,?,?,?,?)
                """, actor, OPERATION, key, requestHash, journalId);
        audit.append("JOURNAL", journalId, actor, "OPENING_BALANCE_POSTED", correlationId, reason);
        return new FundingResponse(journalId, customerAccountId, amount, "COP");
    }

    private void insertPosting(UUID journalId, UUID accountId, String side, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO posting(id, journal_id, account_id, side, amount, currency)
                VALUES(?, ?, ?, ?, ?, 'COP')
                """, UUID.randomUUID(), journalId, accountId, side, amount);
    }

    private void advisoryLock(String scope) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, scope);
                statement.execute();
            }
            return null;
        });
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private record ExistingKey(String requestHash, UUID journalId) { }
}
