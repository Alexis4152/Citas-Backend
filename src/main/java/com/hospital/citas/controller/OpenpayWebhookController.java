package com.hospital.citas.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospital.citas.payment.OpenpayProperties;
import com.hospital.citas.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Eventos asíncronos de OpenPay (ej. el cliente ya hizo su transferencia SPEI). Es público
 * (OpenPay no tiene sesión) pero exige las credenciales Basic configuradas en
 * {@code openpay.webhook-user/password}; sin ellas configuradas, se rechaza todo. */
@RestController
@RequestMapping("/api/webhooks/openpay")
@RequiredArgsConstructor
public class OpenpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(OpenpayWebhookController.class);

    private final PaymentService paymentService;
    private final OpenpayProperties properties;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(String type, Transaction transaction) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Transaction(String id, String authorization, String status,
                                  @com.fasterxml.jackson.annotation.JsonProperty("error_message") String errorMessage) {}
    }

    @PostMapping
    public ResponseEntity<Void> handle(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                        @RequestBody Payload payload) {
        if (!isAuthorized(auth)) {
            log.warn("Webhook de OpenPay rechazado: credenciales inválidas");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (payload != null && payload.transaction() != null && payload.transaction().id() != null) {
            paymentService.processWebhook(payload.type(), payload.transaction().id(),
                    payload.transaction().authorization(), payload.transaction().errorMessage());
        }
        // Siempre 200 para que OpenPay no siga reintentando eventos que no aplican.
        return ResponseEntity.ok().build();
    }

    private boolean isAuthorized(String header) {
        String user = properties.getWebhookUser();
        String password = properties.getWebhookPassword();
        if (user == null || user.isBlank() || password == null || password.isBlank()
                || header == null || !header.startsWith("Basic ")) {
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            String expected = user + ":" + password;
            return MessageDigest.isEqual(decoded.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
