package com.wellnessz.bff;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;

import com.wellnessz.secrets.AesGcmCipher;
import com.wellnessz.secrets.Hkdf;
import com.wellnessz.secrets.MasterKey;

/**
 * Session attributes (which include the user's OAuth2 access and refresh tokens) are encrypted with AES-256-GCM
 * before Spring Session writes them to PostgreSQL, so a database dump never exposes usable tokens.
 */
@Configuration
public class EncryptedSessionConfig {

    @Bean("springSessionConversionService")
    ConversionService springSessionConversionService(@Value("${WZ_MASTER_KEY:}") String key,
                                                     @Value("${WZ_MASTER_KEY_FILE:}") String keyFile) {
        byte[] master = MasterKey.load(n -> MasterKey.ENV_KEY.equals(n) ? key : keyFile);
        if (master == null) {
            throw new IllegalStateException("WZ_MASTER_KEY or WZ_MASTER_KEY_FILE is required to encrypt BFF sessions");
        }
        AesGcmCipher cipher = new AesGcmCipher(Hkdf.derive(master, "wellnessz/bff-session/v1", 32));
        SerializingConverter serializer = new SerializingConverter();
        DeserializingConverter deserializer = new DeserializingConverter(EncryptedSessionConfig.class.getClassLoader());
        GenericConversionService service = new GenericConversionService();
        service.addConverter(Object.class, byte[].class, source -> cipher
                .encrypt(Base64.getEncoder().encodeToString(serializer.convert(source)), "bff-session")
                .getBytes(StandardCharsets.US_ASCII));
        service.addConverter(byte[].class, Object.class, source -> deserializer.convert(
                Base64.getDecoder().decode(cipher.decrypt(new String(source, StandardCharsets.US_ASCII), "bff-session"))));
        return service;
    }
}
