package com.finbridge.paymenthub;

import com.finbridge.paymenthub.shared.DomainProblem;
import com.finbridge.paymenthub.transfer.domain.InternalTransferOrder;
import com.finbridge.paymenthub.transfer.domain.TransferFingerprint;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class TransferDomainTest {
    private final UUID source = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test
    void canonicalizacion_normaliza_escala_espacios_y_unicode() {
        InternalTransferOrder a = InternalTransferOrder.of(source, target,
                new BigDecimal("25000.0"), "COP", "  Nómina ");
        InternalTransferOrder b = InternalTransferOrder.of(source, target,
                new BigDecimal("25000.00"), "COP", "No\u0301mina");
        assertThat(a.amount()).isEqualByComparingTo("25000.00");
        assertThat(a.reference()).isEqualTo("Nómina");
        assertThat(TransferFingerprint.sha256(a)).isEqualTo(TransferFingerprint.sha256(b));
        assertThat(TransferFingerprint.sha256(InternalTransferOrder.of(source, target,
                new BigDecimal("25001.00"), "COP", "Nómina")))
                .isNotEqualTo(TransferFingerprint.sha256(a));
    }

    @Test
    void errores_de_negocio_y_de_sintaxis_tienen_http_distinto() {
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> InternalTransferOrder.of(source, source, new BigDecimal("10.00"), "COP", null));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> InternalTransferOrder.of(source, target, new BigDecimal("10.00"), "USD", null));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> InternalTransferOrder.of(source, target, new BigDecimal("50000000.01"), "COP", null));
        assertProblem(HttpStatus.BAD_REQUEST,
                () -> InternalTransferOrder.of(source, target, new BigDecimal("0"), "COP", null));
        assertProblem(HttpStatus.BAD_REQUEST,
                () -> InternalTransferOrder.of(source, target, new BigDecimal("2.001"), "COP", null));
    }

    private void assertProblem(HttpStatus code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(DomainProblem.class,
                error -> assertThat(error.status()).isEqualTo(code));
    }
}
