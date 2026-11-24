package com.finbridge.paymenthub.transfer.application;

import com.finbridge.paymenthub.account.infrastructure.AccountBalanceEntity;
import com.finbridge.paymenthub.account.infrastructure.AccountBalanceRepository;
import com.finbridge.paymenthub.shared.AuditWriter;
import com.finbridge.paymenthub.security.CurrentActor;
import com.finbridge.paymenthub.shared.DomainProblem;
import com.finbridge.paymenthub.shared.RequestContext;
import com.finbridge.paymenthub.transfer.api.InternalTransferRequest;
import com.finbridge.paymenthub.transfer.api.TransferResponse;
import com.finbridge.paymenthub.transfer.domain.InternalTransferOrder;
import com.finbridge.paymenthub.transfer.domain.TransferFingerprint;
import com.finbridge.paymenthub.transfer.infrastructure.BankAccountLockRepository;
import com.finbridge.paymenthub.transfer.infrastructure.JdbcTransferStore;
import com.finbridge.paymenthub.transfer.infrastructure.TransferIdempotencyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACID: comprobación bajo bloqueo, transferencia, ledger, saldos por trigger, clave y auditoría. */
@Service
public class InternalTransferService {
    private static final String OPERATION = "INTERNAL_TRANSFER";
    private final BankAccountLockRepository accountLocks;
    private final AccountBalanceRepository balances;
    private final TransferIdempotencyRepository idempotency;
    private final JdbcTransferStore transfers;
    private final AuditWriter audit;

    public InternalTransferService(BankAccountLockRepository accountLocks,
                                   AccountBalanceRepository balances,
                                   TransferIdempotencyRepository idempotency,
                                   JdbcTransferStore transfers, AuditWriter audit) {
        this.accountLocks = accountLocks;
        this.balances = balances;
        this.idempotency = idempotency;
        this.transfers = transfers;
        this.audit = audit;
    }

    @Transactional
    public TransferResponse transfer(InternalTransferRequest request, String key, String correlationHeader) {
        CurrentActor.require("CUSTOMER");
        TransferIdempotencyRepository.validateKey(key);
        InternalTransferOrder order = InternalTransferOrder.of(request.sourceAccountId(),
                request.destinationAccountId(), request.amount(), request.currency(), request.reference());
        String hash = TransferFingerprint.sha256(order);
        String correlationId = RequestContext.correlationId(correlationHeader);
        UUID actor = CurrentActor.id();

        // Primero serializar la MISMA clave; replay incluso si luego cambia el estado de una cuenta.
        idempotency.lock(actor, OPERATION, key);
        var previous = idempotency.find(actor, OPERATION, key);
        if (previous.isPresent()) {
            if (!previous.get().requestHash().equals(hash))
                throw DomainProblem.conflict("Idempotency-Key reutilizada con un cuerpo diferente");
            var owners = transfers.owners(previous.get().resourceId());
            CurrentActor.requireOwner(owners.source());
            CurrentActor.requireOwner(owners.destination());
            return transfers.find(previous.get().resourceId());
        }

        // Bloqueamos las filas de ambas cuentas y luego sus saldos, en el MISMO orden UUID.
        // Los bloqueos account FOR SHARE inmovilizan status/customerId durante la transacción.
        List<UUID> ordered = new ArrayList<>(List.of(order.source(), order.destination()));
        ordered.sort(UUID::compareTo);
        Map<UUID, BankAccountLockRepository.LockedAccount> lockedAccounts = new HashMap<>();
        for (UUID id : ordered) lockedAccounts.put(id, accountLocks.lock(id));
        var source = lockedAccounts.get(order.source());
        var destination = lockedAccounts.get(order.destination());
        CurrentActor.requireOwner(source.customerId());
        CurrentActor.requireOwner(destination.customerId());
        if (!"ACTIVE".equals(source.status()) || !"ACTIVE".equals(destination.status()))
            throw DomainProblem.unprocessable("Ambas cuentas deben estar ACTIVE");
        if (!"COP".equals(source.currency()) || !"COP".equals(destination.currency()))
            throw DomainProblem.unprocessable("Moneda de las cuentas incompatible");
        if (!"CUSTOMER_LIABILITY".equals(source.accountType()) ||
                !"CUSTOMER_LIABILITY".equals(destination.accountType()))
            throw DomainProblem.unprocessable("Solo cuentas de cliente admiten transferencias internas");

        Map<UUID, AccountBalanceEntity> lockedBalances = new HashMap<>();
        for (UUID id : ordered) {
            lockedBalances.put(id, balances.lockById(id)
                    .orElseThrow(() -> DomainProblem.notFound("Proyección contable")));
        }
        if (lockedBalances.get(order.source()).getAvailableAmount().compareTo(order.amount()) < 0)
            throw DomainProblem.unprocessable("Saldo disponible insuficiente");

        UUID transferId = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        transfers.insert(transferId, order, correlationId);
        transfers.bookJournal(journalId, transferId, order);
        idempotency.save(actor, OPERATION, key, hash, transferId);
        audit.append("TRANSFER", transferId, actor, "INTERNAL_TRANSFER_COMPLETED",
                correlationId, "Asiento " + journalId + ", COP; transferencia interna confirmada");
        return transfers.find(transferId);
    }

    @Transactional(readOnly = true)
    public TransferResponse find(UUID id) {
        var owners = transfers.owners(id);
        if (!(CurrentActor.has("OPERATIONS") || CurrentActor.has("AUDITOR") || CurrentActor.has("ADMIN"))) {
            CurrentActor.require("CUSTOMER");
            CurrentActor.requireOwner(owners.source());
            CurrentActor.requireOwner(owners.destination());
        }
        return transfers.find(id);
    }
}
