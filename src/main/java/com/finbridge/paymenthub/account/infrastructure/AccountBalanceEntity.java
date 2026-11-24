package com.finbridge.paymenthub.account.infrastructure;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "account_balance")
public class AccountBalanceEntity {
    @Id @Column(name = "account_id")
    private UUID accountId;
    @Column(name = "available_amount", nullable = false, precision = 19, scale = 2,
            insertable = false, updatable = false)
    private BigDecimal availableAmount = BigDecimal.ZERO;
    @Column(nullable = false, insertable = false, updatable = false)
    private long version;

    protected AccountBalanceEntity() { }
    public AccountBalanceEntity(UUID accountId) { this.accountId = accountId; }
    public UUID getAccountId() { return accountId; }
    public BigDecimal getAvailableAmount() { return availableAmount; }
    public long getVersion() { return version; }
}
