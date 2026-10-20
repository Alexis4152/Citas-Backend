package com.hospital.citas.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Cifra/descifra de forma transparente los campos marcados con
 * {@code @Convert(converter = EncryptedStringConverter.class)}: ningún punto de guardado puede
 * "olvidar" cifrar (patrón de DemoPV).
 */
@Converter
@Component
@RequiredArgsConstructor
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final CredentialEncryptionService encryption;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryption.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryption.decrypt(dbData);
    }
}
