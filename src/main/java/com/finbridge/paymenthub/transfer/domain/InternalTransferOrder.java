package com.finbridge.paymenthub.transfer.domain;

import com.finbridge.paymenthub.shared.DomainProblem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.UUID;

/** Invariantes del caso de uso, sin dependencia de Spring MVC/JPA. */
public record InternalTransferOrder(UUID source, UUID destination, BigDecimal amount,
                                    String currency, String reference) {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("50000000.00");

    public static InternalTransferOrder of(UUID source, UUID destination,
                                            BigDecimal amount, String currency, String reference) {
        if (source == null || destination == null || amount == null || currency == null)
            throw DomainProblem.badRequest("sourceAccountId, destinationAccountId, amount y currency son obligatorios");
        if (source.equals(destination))
            throw DomainProblem.unprocessable("La cuenta origen y destino deben ser distintas");
        if (!"COP".equals(currency))
            throw DomainProblem.unprocessable("Solo se admiten transferencias en COP");
        // La forma decimal no admite redondeos implícitos ni valores con más de dos decimales reales.
        BigDecimal exact;
        try {
            exact = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw DomainProblem.badRequest("amount debe tener precisión de centavos (máximo dos decimales)");
        }
        if (exact.signum() <= 0)
            throw DomainProblem.badRequest("amount debe ser mayor que cero");
        if (exact.compareTo(MAX_AMOUNT) > 0)
            throw DomainProblem.unprocessable("Límite por transferencia: 50.000.000,00 COP");

        String normalized = reference == null ? null : Normalizer.normalize(reference.strip(), Normalizer.Form.NFC);
        if (normalized != null && normalized.isEmpty()) normalized = null;
        if (normalized != null && (normalized.length() > 140 || normalized.codePoints()
                .anyMatch(Character::isISOControl)))
            throw DomainProblem.badRequest("reference debe tener hasta 140 caracteres, sin caracteres de control");
        return new InternalTransferOrder(source, destination, exact, currency, normalized);
    }
}
