package com.hospital.citas.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.exception.PaymentGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cliente REST de OpenPay (misma integración que el DemoPV): autenticación básica con la llave
 * privada, cargos con tarjeta tokenizada en el navegador (los datos de la tarjeta nunca llegan
 * a este backend), cargos SPEI y reembolsos. Los errores de tarjeta (códigos 3xxx) se traducen
 * a un mensaje claro para el cliente; el resto, a {@link PaymentGatewayException}.
 */
@Component
public class OpenpayPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenpayPaymentGateway.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenpayPaymentGateway(OpenpayProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        var factory = new SimpleClientHttpRequestFactory();
        int timeout = properties.getTimeoutSeconds() > 0 ? properties.getTimeoutSeconds() : 15;
        factory.setConnectTimeout(Duration.ofSeconds(timeout));
        factory.setReadTimeout(Duration.ofSeconds(timeout));
        String root = (properties.getBaseUrl() != null ? properties.getBaseUrl().replaceAll("/+$", "") : "https://sandbox-api.openpay.mx/v1")
                + "/" + (properties.getMerchantId() != null ? properties.getMerchantId() : "");
        this.restClient = RestClient.builder()
                .baseUrl(root)
                .requestFactory(factory)
                .defaultHeaders(h -> {
                    h.setBasicAuth(properties.getPrivateKey() != null ? properties.getPrivateKey() : "", "", StandardCharsets.UTF_8);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Accept", MediaType.APPLICATION_JSON_VALUE);
                })
                .build();
    }

    @Override
    public GatewayResult charge(GatewayCharge charge) {
        var customer = charge.customer() == null ? null : new CustomerDto(
                charge.customer().name(), charge.customer().lastName(), charge.customer().email(), charge.customer().phoneNumber());
        var body = new ChargeRequest(
                charge.kind() == Kind.CARD ? "card" : "bank_account",
                charge.sourceId(), charge.amount(), charge.currency(), charge.description(), charge.orderId(),
                charge.deviceSessionId(), customer,
                // Tarjeta: cobro directo. SPEI: se confirma después, cuando el cliente transfiere.
                charge.kind() == Kind.CARD);
        return call(() -> restClient.post().uri("/charges").body(body), "crear el cargo");
    }

    @Override
    public GatewayResult getCharge(String id) {
        return call(() -> restClient.get().uri("/charges/{id}", id), "consultar el cargo");
    }

    @Override
    public GatewayResult refund(String id, BigDecimal amount, String reason) {
        return call(() -> restClient.post().uri("/charges/{id}/refund", id).body(new RefundRequest(reason, amount)),
                "reembolsar el cargo");
    }

    private GatewayResult call(java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> request, String action) {
        try {
            ChargeResponse response = request.get().retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) ->
                            handleError(resp.getStatusCode().value(), new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8)))
                    .body(ChargeResponse.class);
            if (response == null) {
                throw new PaymentGatewayException("La pasarela de pagos devolvió una respuesta vacía");
            }
            return toResult(response);
        } catch (BusinessException | PaymentGatewayException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error inesperado al {} en OpenPay", action, e);
            throw new PaymentGatewayException("No se pudo comunicar con la pasarela de pagos. Intenta de nuevo en unos minutos.", e);
        }
    }

    private void handleError(int httpStatus, String body) {
        log.warn("OpenPay respondió HTTP {}: {}", httpStatus, body);
        try {
            ErrorResponse error = objectMapper.readValue(body, ErrorResponse.class);
            Integer code = error.errorCode();
            String description = error.description() != null ? error.description() : "Error desconocido en la pasarela";
            // 3xxx = tarjeta declinada, expirada, sin fondos o sospecha de fraude: error del cliente, no del sistema.
            if (code != null && code >= 3000 && code < 4000) {
                throw new BusinessException("El pago fue rechazado: " + friendly(code, description));
            }
            if (code != null && code >= 1000 && code < 2000 && httpStatus < 500) {
                throw new BusinessException("No se pudo procesar el pago: " + friendly(code, description));
            }
            throw new PaymentGatewayException("Error en la pasarela de pagos [" + code + "]: " + description);
        } catch (BusinessException | PaymentGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentGatewayException("La pasarela de pagos respondió con un error (HTTP " + httpStatus + ")");
        }
    }

    private String friendly(int code, String fallback) {
        return switch (code) {
            case 3001 -> "la tarjeta fue declinada por el banco emisor.";
            case 3002 -> "la tarjeta ha expirado.";
            case 3003 -> "fondos insuficientes en la tarjeta.";
            case 3004 -> "tarjeta rechazada por reporte de extravío o robo.";
            case 3005 -> "transacción rechazada por el sistema antifraude.";
            default -> fallback;
        };
    }

    private GatewayResult toResult(ChargeResponse r) {
        Status status = switch (r.status() == null ? "" : r.status().toLowerCase()) {
            case "completed" -> Status.COMPLETED;
            case "in_progress" -> Status.IN_PROGRESS;
            case "failed" -> Status.FAILED;
            case "cancelled" -> Status.CANCELLED;
            case "refunded" -> Status.REFUNDED;
            default -> Status.PENDING;
        };
        PaymentMethodDto pm = r.paymentMethod();
        return new GatewayResult(r.id(), status, r.authorization(),
                pm != null ? pm.clabe() : null, pm != null ? pm.bank() : null, r.errorMessage());
    }

    // ── DTOs del API de OpenPay ──────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChargeRequest(String method, @JsonProperty("source_id") String sourceId, BigDecimal amount, String currency,
                         String description, @JsonProperty("order_id") String orderId,
                         @JsonProperty("device_session_id") String deviceSessionId, CustomerDto customer, Boolean confirm) {}

    record CustomerDto(String name, @JsonProperty("last_name") String lastName, String email,
                       @JsonProperty("phone_number") String phoneNumber) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RefundRequest(String description, BigDecimal amount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChargeResponse(String id, String authorization, String status, BigDecimal amount, String currency,
                          @JsonProperty("error_message") String errorMessage,
                          @JsonProperty("payment_method") PaymentMethodDto paymentMethod) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentMethodDto(String type, String clabe, String bank) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(String description, @JsonProperty("http_code") Integer httpCode,
                         @JsonProperty("error_code") Integer errorCode) {}
}
