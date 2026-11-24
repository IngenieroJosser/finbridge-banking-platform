package com.finbridge.paymenthub.account.api;

import com.finbridge.paymenthub.account.domain.AccountStatus;
import java.util.UUID;

public record AccountResponse(UUID id, UUID customerId, String currency, AccountStatus status) { }
