package com.hospital.citas.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuración de la pasarela OpenPay (prefijo {@code openpay.*}). Por defecto apunta al
 * sandbox con las llaves de prueba; en producción TODAS se sobreescriben con variables de
 * entorno (ver application.properties) y {@code production=true}. */
@Component
@ConfigurationProperties(prefix = "openpay")
@Getter @Setter
public class OpenpayProperties {
    private String merchantId;
    private String privateKey;
    private String publicKey;
    private String baseUrl = "https://sandbox-api.openpay.mx/v1";
    private boolean production = false;
    private int timeoutSeconds = 15;
    private String webhookUser;
    private String webhookPassword;
}
