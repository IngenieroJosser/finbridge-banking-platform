package com.finbridge.paymenthub.transfer.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** Contrato de entrada: los errores de negocio se validan en el dominio. */
public record InternalTransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @NotNull BigDecimal amount,
        @NotBlank String currency,
        String reference) { }
