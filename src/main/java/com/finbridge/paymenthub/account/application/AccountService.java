package com.finbridge.paymenthub.account.application;

import com.finbridge.paymenthub.account.api.*;
import com.finbridge.paymenthub.account.infrastructure.*;
import com.finbridge.paymenthub.shared.*;
import com.finbridge.paymenthub.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final AccountBalanceRepository balances;
    private final AuditWriter audit;

    public AccountService(AccountRepository accounts, CustomerRepository customers,
                          AccountBalanceRepository balances, AuditWriter audit) {
        this.accounts = accounts;
        this.customers = customers;
        this.balances = balances;
        this.audit = audit;
    }

    @Transactional
    public AccountResponse open(CreateAccountRequest request, String correlationHeader) {
        CurrentActor.require("OPERATIONS", "ADMIN");
        CustomerEntity customer = customers.findById(request.customerId())
                .orElseThrow(() -> DomainProblem.badRequest("customerId no registrado"));
        if (!customer.isActive()) throw DomainProblem.unprocessable("Cliente bloqueado");
        AccountEntity account = AccountEntity.newCopCustomerAccount(request.customerId());
        accounts.saveAndFlush(account); // FK account_balance requiere la cuenta persistida.
        balances.saveAndFlush(new AccountBalanceEntity(account.getId()));
        audit.append("ACCOUNT", account.getId(), CurrentActor.id(),
                "ACCOUNT_OPENED", RequestContext.correlationId(correlationHeader), "COP: balance inicial cero");
        return new AccountResponse(account.getId(), account.getCustomerId(),
                account.getCurrency(), account.getStatus());
    }

    @Transactional(readOnly = true)
    public AccountResponse find(UUID accountId) {
        AccountEntity account = accounts.findById(accountId)
                .orElseThrow(() -> DomainProblem.notFound("Cuenta"));
        CurrentActor.readAccount(account.getCustomerId());
        return new AccountResponse(account.getId(), account.getCustomerId(),
                account.getCurrency(), account.getStatus());
    }

    @Transactional(readOnly = true)
    public BalanceResponse balance(UUID accountId) {
        AccountEntity account = accounts.findById(accountId)
                .orElseThrow(() -> DomainProblem.notFound("Cuenta"));
        CurrentActor.readAccount(account.getCustomerId());
        AccountBalanceEntity balance = balances.findById(accountId)
                .orElseThrow(() -> DomainProblem.notFound("Saldo de cuenta"));
        return new BalanceResponse(accountId, account.getCurrency(), balance.getAvailableAmount());
    }
}
