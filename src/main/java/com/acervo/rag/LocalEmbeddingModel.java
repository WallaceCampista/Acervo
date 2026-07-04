package com.acervo.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EmbeddingModel para endpoints OpenAI-compat locais (LM Studio, Ollama,
 * vLLM, etc) que falam /v1/embeddings mas não preenchem o campo `usage`.
 *
 * <p>O Spring AI 1.0.0-M3 tem um Assert.notNull no parsing do response do
 * OpenAiEmbeddingModel — quando o provider devolve usage=null (caso do
 * LM Studio em chat/embedding), a indexação quebra com "OpenAI Usage must
 * not be null". Esta classe vai direto na API e ignora o usage.
 */
@Component
@Slf4j
public class LocalEmbeddingModel implements EmbeddingModel {

    private final RestClient client;
    private final String model;
    private final int dimensions;

    public LocalEmbeddingModel(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key:lm-studio}") String apiKey,
            @Value("${acervo.embedding.model}") String model,
            @Value("${acervo.embedding.dimensions:768}") int dimensions) {
        this.model = model;
        this.dimensions = dimensions;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        log.info("LocalEmbeddingModel ativo: base={}, model={}, dims={}", baseUrl, model, dimensions);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        if (inputs == null || inputs.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }

        Map response = client.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "input", inputs))
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> data = response != null
                ? (List<Map<String, Object>>) response.get("data")
                : List.of();

        List<Embedding> result = new ArrayList<>(data.size());
        for (Map<String, Object> item : data) {
            List<Number> values = (List<Number>) item.get("embedding");
            float[] vec = new float[values.size()];
            for (int j = 0; j < values.size(); j++) vec[j] = values.get(j).floatValue();
            int index = item.get("index") instanceof Number n ? n.intValue() : result.size();
            result.add(new Embedding(vec, index));
        }

        if (result.size() != inputs.size()) {
            log.warn("LM Studio retornou {} embeddings para {} entradas",
                    result.size(), inputs.size());
        }
        return new EmbeddingResponse(result);
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        EmbeddingResponse response = call(new EmbeddingRequest(
                List.of(document.getContent()), null));
        return response.getResults().isEmpty()
                ? new float[0]
                : response.getResults().get(0).getOutput();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
