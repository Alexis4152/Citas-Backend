package com.hospital.citas.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra las credenciales que se guardan en BD (llave privada de OpenPay de cada hospital y
 * contraseña SMTP) con AES-256-GCM (patrón de DemoPV / nexora-hotel): quien obtenga un respaldo
 * de la base ya no tiene con eso las llaves para cobrar a nombre del hospital.
 * <p>
 * La llave sale de {@code APP_ENCRYPTION_KEY}. Si no está definida se deriva de
 * {@code APP_JWT_SECRET} (con aviso en el log), para no romper un despliegue que aún no la tiene.
 * <p>
 * Los valores cifrados llevan el prefijo {@code enc:v1:}. Los que ya estaban guardados en claro
 * (antes de este cambio) se siguen leyendo tal cual y se cifran la próxima vez que se guardan.
 */
@Component
public class CredentialEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionService.class);
    static final String PREFIX = "enc:v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialEncryptionService(@Value("${app.encryption.key:}") String encryptionKey,
                                       @Value("${app.jwt.secret}") String jwtSecret) {
        String material = encryptionKey;
        if (material == null || material.isBlank()) {
            log.warn("APP_ENCRYPTION_KEY no está definida: las credenciales se cifran con una llave derivada de "
                    + "APP_JWT_SECRET. Define APP_ENCRYPTION_KEY propia (openssl rand -base64 48) antes de cargar llaves reales.");
            material = "credential-encryption|" + jwtSecret;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo derivar la llave de cifrado", e);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        if (plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar el valor", e);
        }
    }

    /** Si el valor no se puede descifrar (cambió la llave), se trata como no configurado y se
     * avisa en el log: el admin solo tiene que volver a capturarlo. Nunca tumba la petición. */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, combined, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("No se pudo descifrar una credencial guardada (¿cambió APP_ENCRYPTION_KEY?). "
                    + "Se trata como no configurada; vuelve a capturarla en el panel.");
            return null;
        }
    }
}
