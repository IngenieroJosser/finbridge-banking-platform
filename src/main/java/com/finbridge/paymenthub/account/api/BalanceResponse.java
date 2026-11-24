package com.finbridge.paymenthub.account.api;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceResponse(UUID accountId, String currency, BigDecimal availableBalance) { }
