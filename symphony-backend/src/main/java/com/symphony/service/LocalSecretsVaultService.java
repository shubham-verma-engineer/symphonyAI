package com.symphony.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SecretsVaultService} for local offline runs.
 *
 * <p>Holds AES-256-GCM ciphertext in a process-local map, so stored secrets do
 * not survive a restart. Restricted to mock mode: outside it,
 * {@code HashiCorpSecretsVaultService} is wired instead, and
 * {@code ConfigurationGuard} refuses to start a production instance backed by a
 * non-durable vault.
 */
@Slf4j
public class LocalSecretsVaultService implements SecretsVaultService {

    @PostConstruct
    void warnAboutNonDurability() {
        log.warn("Using the in-memory secrets vault: stored agent credentials will be LOST on restart, "
                + "while the agents.credential_vault_uri rows pointing at them will persist. "
                + "Local development only.");
    }

    static final String DEFAULT_DEV_KEY = "this-is-a-32-byte-encryption-key-for-local-vault";

    private final SecretKeySpec secretKey;
    private final Map<String, String> encryptedStore = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public LocalSecretsVaultService(String rawKey) {
        // Local development only, so an unset key derives a working one rather
        // than failing the boot. ConfigurationGuard separately refuses to start
        // production on the known default, and this implementation is never the
        // production choice.
        String key = (rawKey == null || rawKey.isBlank()) ? DEFAULT_DEV_KEY : rawKey;
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize AES key derivation", e);
        }
    }

    /**
     * Always false: secrets live in an in-process map and are lost on restart.
     * Stated explicitly because {@code ConfigurationGuard} refuses to start a
     * production instance backed by a non-durable vault.
     */
    @Override
    public boolean isDurable() {
        return false;
    }

    @Override
    public String storeSecret(String path, String secretValue) {
        if (secretValue == null) return null;
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherText = cipher.doFinal(secretValue.getBytes(StandardCharsets.UTF_8));
            byte[] encryptedData = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, encryptedData, 0, iv.length);
            System.arraycopy(cipherText, 0, encryptedData, iv.length, cipherText.length);

            String base64Encrypted = Base64.getEncoder().encodeToString(encryptedData);
            String uri = "vault:/" + path;
            encryptedStore.put(uri, base64Encrypted);
            return uri;
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt and store secret in local vault", e);
        }
    }

    @Override
    public String retrieveSecret(String vaultUri) {
        if (vaultUri == null) return null;
        String base64Encrypted = encryptedStore.get(vaultUri);
        if (base64Encrypted == null) return null;

        try {
            byte[] encryptedData = Base64.getDecoder().decode(base64Encrypted);
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
            throw new RuntimeException("Failed to retrieve and decrypt secret from local vault", e);
        }
    }

    @Override
    public void deleteSecret(String vaultUri) {
        if (vaultUri != null) {
            encryptedStore.remove(vaultUri);
        }
    }
}
