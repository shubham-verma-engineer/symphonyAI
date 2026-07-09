package com.symphony.service.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiEmbeddingClient {

    public static final String MODEL = "text-embedding-3-small";
    public static final int DIMENSIONS = 1536;

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");

    @Value("${openai.api-key:mock-key}")
    private String apiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String baseUrl;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(20))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random(42); // Seeded for deterministic mock outputs

    public float[] embed(String text) throws IOException {
        if ("mock-key".equalsIgnoreCase(apiKey)) {
            log.info("Generating mock embedding for text (mock-key active)");
            float[] mockVec = new float[DIMENSIONS];
            double sumSq = 0;
            // Generate unit vector
            for (int i = 0; i < DIMENSIONS; i++) {
                mockVec[i] = random.nextFloat() - 0.5f;
                sumSq += mockVec[i] * mockVec[i];
            }
            double norm = Math.sqrt(sumSq);
            for (int i = 0; i < DIMENSIONS; i++) {
                mockVec[i] = (float) (mockVec[i] / norm);
            }
            return mockVec;
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", MODEL);
        ArrayNode inputArr = payload.putArray("input");
        inputArr.add(text);

        Request req = new Request.Builder()
                .url(baseUrl + "/v1/embeddings")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(mapper.writeValueAsBytes(payload), JSON_MEDIA))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            ResponseBody body = resp.body();
            if (!resp.isSuccessful()) {
                String err = body != null ? body.string() : "";
                throw new IOException("OpenAI embeddings error " + resp.code() + ": " + err);
            }
            if (body == null) throw new IOException("OpenAI embeddings returned empty body");

            JsonNode root = mapper.readTree(body.byteStream());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IOException("OpenAI returned no embeddings");
            }
            JsonNode emb = data.get(0).path("embedding");
            if (!emb.isArray() || emb.size() != DIMENSIONS) {
                throw new IOException("OpenAI embedding has " + emb.size() + " dims, expected " + DIMENSIONS);
            }
            float[] vec = new float[DIMENSIONS];
            for (int i = 0; i < DIMENSIONS; i++) {
                vec[i] = (float) emb.get(i).asDouble();
            }
            return vec;
        }
    }
}
