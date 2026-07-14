package com.symphony.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Converter
@Component
public class AesGcmEncryptor implements AttributeConverter<String, String> {

    private static SecretKeySpec secretKey;
    private final SecureRandom random = new SecureRandom();

    private static String rawKey;

    @Value("${symphony.database.encryption-key:this-is-a-32-byte-db-encryption-key}")
    public void setRawKey(String key) {
        rawKey = key;
        initSecretKey();
    }

    private static synchronized void initSecretKey() {
        try {
            String keySource = rawKey != null ? rawKey : "this-is-a-32-byte-db-encryption-key";
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(keySource.getBytes(StandardCharsets.UTF_8));
            secretKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database AES key", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            if (secretKey == null) {
                initSecretKey();
            }
            byte[] iv = new byte[12];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherText = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] encryptedData = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, encryptedData, 0, iv.length);
            System.arraycopy(cipherText, 0, encryptedData, iv.length, cipherText.length);

            return Base64.getEncoder().encodeToString(encryptedData);
        } catch (Exception e) {
            throw new RuntimeException("Error encrypting column value", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            if (secretKey == null) {
                initSecretKey();
            }
            byte[] encryptedData = Base64.getDecoder().decode(dbData);
            byte[] iv = new byte[12];
            System.arraycopy(encryptedData, 0, iv, 0, 12);

            byte[] cipherText = new byte[encryptedData.length - 12];
            System.arraycopy(encryptedData, 12, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] decryptedBytes = cipher.doFinal(cipherText);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Error decrypting column value", e);
        }
    }
}
