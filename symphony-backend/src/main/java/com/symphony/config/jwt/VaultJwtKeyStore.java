package com.symphony.config.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.Versioned;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists the JWT key ring in a HashiCorp Vault KV v2 mount.
 *
 * <p>Keeping key material in Vault rather than in configuration is the actual
 * fix for B2: the signing key leaves the repository entirely, and because RS256
 * is asymmetric, possessing the verification key no longer implies the ability
 * to mint tokens. Replicas share one ring, so a token signed by one instance
 * verifies on another, and a restart does not sign everyone out.
 *
 * <p>The ring is stored as a single JSON document so that writing it is one
 * atomic KV operation — a ring split across keys could be read mid-write with
 * an active key that does not match its retired set.
 */
@Slf4j
public class VaultJwtKeyStore implements JwtKeyStore {

    private static final String FIELD = "keyring";

    private final VaultTemplate vaultTemplate;
    private final String mount;
    private final String path;
    private final ObjectMapper mapper = new ObjectMapper();

    public VaultJwtKeyStore(VaultTemplate vaultTemplate, String mount, String path) {
        this.vaultTemplate = vaultTemplate;
        this.mount = mount;
        this.path = path;
    }

    @Override
    public boolean isDurable() {
        return true;
    }

    @Override
    public Optional<JwtKeyRing> load() {
        try {
            Versioned<Map<String, Object>> versioned =
                    vaultTemplate.opsForVersionedKeyValue(mount).get(path);
            if (versioned == null || !versioned.hasData() || versioned.getData() == null) {
                return Optional.empty();
            }
            Object raw = versioned.getData().get(FIELD);
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(deserialise(raw.toString()));
        } catch (Exception e) {
            // Starting with a fresh key here would silently invalidate every
            // issued token and mask a Vault outage or a permissions problem, so
            // the boot fails instead.
            throw new IllegalStateException(
                    "Could not read the JWT key ring from Vault at " + mount + "/" + path
                    + ". Refusing to start rather than generating a new key and invalidating all "
                    + "issued tokens. Cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void save(JwtKeyRing keyRing) {
        try {
            vaultTemplate.opsForVersionedKeyValue(mount)
                    .put(path, Map.of(FIELD, serialise(keyRing)));
            log.info("Persisted JWT key ring to Vault at {}/{} (active kid={})",
                    mount, path, keyRing.active().kid());
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the JWT key ring to Vault at "
                    + mount + "/" + path + ": " + e.getMessage(), e);
        }
    }

    private String serialise(JwtKeyRing keyRing) {
        ObjectNode root = mapper.createObjectNode();
        root.set("active", keyNode(keyRing.active()));
        ArrayNode retired = root.putArray("retired");
        keyRing.retired().forEach(k -> retired.add(keyNode(k)));
        return root.toString();
    }

    private ObjectNode keyNode(JwtKeyRing.Key key) {
        ObjectNode node = mapper.createObjectNode();
        node.put("kid", key.kid());
        node.put("publicKey", Base64.getEncoder().encodeToString(key.publicKey().getEncoded()));
        if (key.privateKey() != null) {
            node.put("privateKey", Base64.getEncoder().encodeToString(key.privateKey().getEncoded()));
        }
        node.put("createdAt", key.createdAt().toString());
        if (key.retiredAt() != null) {
            node.put("retiredAt", key.retiredAt().toString());
        }
        return node;
    }

    private JwtKeyRing deserialise(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JwtKeyRing.Key active = key(root.get("active"));

        List<JwtKeyRing.Key> retired = new ArrayList<>();
        JsonNode retiredNode = root.get("retired");
        if (retiredNode != null && retiredNode.isArray()) {
            for (JsonNode node : retiredNode) {
                retired.add(key(node));
            }
        }
        return new JwtKeyRing(active, retired);
    }

    private JwtKeyRing.Key key(JsonNode node) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");

        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(node.get("publicKey").asText())));

        PrivateKey privateKey = null;
        if (node.hasNonNull("privateKey")) {
            privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(node.get("privateKey").asText())));
        }

        Instant retiredAt = node.hasNonNull("retiredAt") ? Instant.parse(node.get("retiredAt").asText()) : null;
        return new JwtKeyRing.Key(node.get("kid").asText(), privateKey, publicKey,
                Instant.parse(node.get("createdAt").asText()), retiredAt);
    }
}
