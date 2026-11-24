package com.finbridge.paymenthub.account.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "customer")
public class CustomerEntity {
    @Id private UUID id;
    @Column(name = "external_ref", nullable = false, length = 100)
    private String externalRef;
    @Column(nullable = false, length = 16)
    private String status;

    protected CustomerEntity() { }
    public UUID getId() { return id; }
    public boolean isActive() { return "ACTIVE".equals(status); }
}
