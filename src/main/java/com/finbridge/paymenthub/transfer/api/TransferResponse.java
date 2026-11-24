package com.finbridge.paymenthub.transfer.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Proyección pública; nunca expone una entidad JPA o postings del ledger. */
public record TransferResponse(
        UUID id,
        String type,
        String status,
        BigDecimal amount,
        String currency,
        OffsetDateTime createdAt,
        String correlationId) { }
