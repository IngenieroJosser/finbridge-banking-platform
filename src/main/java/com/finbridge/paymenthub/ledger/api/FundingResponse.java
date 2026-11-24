package com.finbridge.paymenthub.ledger.api;

import java.math.BigDecimal;
import java.util.UUID;

public record FundingResponse(UUID journalId, UUID accountId, BigDecimal amount, String currency) { }
