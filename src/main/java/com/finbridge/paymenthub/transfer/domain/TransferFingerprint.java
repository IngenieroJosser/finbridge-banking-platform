package com.finbridge.paymenthub.transfer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versión de canonicalización explícita, reutilizable por HTTP, servicio y pruebas. */
public final class TransferFingerprint {
    private TransferFingerprint() { }

    public static String sha256(InternalTransferOrder order) {
        // La referencia es el último campo y se incluye su longitud para evitar ambigüedades.
        String reference = order.reference() == null ? "" : order.reference();
        String canonical = "internal-transfer:v1\n" + order.source() + "\n" + order.destination()
                + "\n" + order.amount().toPlainString() + "\n" + order.currency() + "\n"
                + reference.length() + ":" + reference;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
