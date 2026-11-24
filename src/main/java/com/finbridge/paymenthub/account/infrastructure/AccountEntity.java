package com.finbridge.paymenthub.account.infrastructure;

import com.finbridge.paymenthub.account.domain.AccountStatus;
import com.finbridge.paymenthub.account.domain.AccountType;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "account")
public class AccountEntity {
    @Id private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(nullable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 24)
    private AccountType accountType;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AccountEntity() { }

    public static AccountEntity newCopCustomerAccount(UUID customerId) {
        AccountEntity account = new AccountEntity();
        account.id = UUID.randomUUID();
        account.customerId = customerId;
        account.currency = "COP";
        account.status = AccountStatus.ACTIVE;
        account.accountType = AccountType.CUSTOMER_LIABILITY;
        return account;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public String getCurrency() { return currency; }
    public AccountStatus getStatus() { return status; }
    public AccountType getAccountType() { return accountType; }
}
