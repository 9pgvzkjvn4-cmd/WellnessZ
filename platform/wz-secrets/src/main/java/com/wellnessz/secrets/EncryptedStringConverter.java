package com.wellnessz.secrets;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.stereotype.Component;

/**
 * JPA converter for clinical text columns: {@code @Convert(converter = EncryptedStringConverter.class)}.
 * Instantiated through Spring (Hibernate's SpringBeanContainer) so it receives the {@link FieldEncryptor}.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String CONTEXT = "wellnessz-field";
    private final FieldEncryptor encryptor;

    public EncryptedStringConverter(FieldEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptor.encrypt(attribute, CONTEXT);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryptor.decrypt(dbData, CONTEXT);
    }
}
