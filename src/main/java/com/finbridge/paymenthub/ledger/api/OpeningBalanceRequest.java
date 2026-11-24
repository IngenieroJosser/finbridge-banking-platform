package com.finbridge.paymenthub.ledger.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record OpeningBalanceRequest(
        @NotNull @DecimalMin(value = "0.00", inclusive = false)
        @DecimalMax("50000000.00") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Size(min = 5, max = 140) String reason) { }
