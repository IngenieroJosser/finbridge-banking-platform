package com.finbridge.paymenthub.account.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateAccountRequest(@NotNull UUID customerId, @NotNull Currency currency) {
    public enum Currency { COP }
}
