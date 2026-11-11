package com.sb24.api.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Transparent column-level encryption for PII (name, phone, national id). Null stays null. */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {
    private final FieldCipher cipher;

    public EncryptedStringConverter(FieldCipher cipher) { this.cipher = cipher; }

    @Override public String convertToDatabaseColumn(String attribute) { return attribute == null ? null : cipher.encrypt(attribute); }

    @Override public String convertToEntityAttribute(String db) { return db == null ? null : cipher.decrypt(db); }
}
