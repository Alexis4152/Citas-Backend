package com.hospital.citas;

import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.payment.PaymentGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Pasarela simulada: ninguna prueba toca la red de OpenPay. El token "tok_declined" simula una
 * tarjeta declinada; SPEI queda "en progreso" con una CLABE de prueba. */
@TestConfiguration
public class FakePaymentGatewayConfig {

    public static class FakeGateway implements PaymentGateway {
        public final List<String> refunds = new CopyOnWriteArrayList<>();
        public final List<GatewayCharge> charges = new CopyOnWriteArrayList<>();
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public GatewayResult charge(GatewayCharge charge) {
            if ("tok_declined".equals(charge.sourceId())) {
                throw new BusinessException("El pago fue rechazado: la tarjeta fue declinada por el banco emisor.");
            }
            charges.add(charge);
            String id = "fake-tx-" + seq.incrementAndGet();
            if (charge.kind() == Kind.SPEI) {
                return new GatewayResult(id, Status.IN_PROGRESS, null, "646180000000000001", "STP", null);
            }
            return new GatewayResult(id, Status.COMPLETED, "AUTH" + id, null, null, null);
        }

        @Override
        public GatewayResult getCharge(String id) {
            return new GatewayResult(id, Status.COMPLETED, "AUTH-" + id, null, null, null);
        }

        @Override
        public GatewayResult refund(String id, BigDecimal amount, String reason) {
            refunds.add(id);
            return new GatewayResult(id, Status.REFUNDED, null, null, null, null);
        }
    }

    @Bean
    @Primary
    public FakeGateway fakePaymentGateway() {
        return new FakeGateway();
    }
}
