package com.acervo.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do MmrReranker com um {@link EmbeddingModel} stub que mapeia
 * cada texto pra um vetor controlado — assim conseguimos prever o
 * comportamento sem depender de IA externa.
 */
class MmrRerankerTest {

    @Test
    @DisplayName("retorna candidates intactos quando já é menor que topN")
    void belowTopNReturnsAsIs() {
        MmrReranker r = new MmrReranker(new StubEmbeddingModel(Map.of()));
        List<Document> docs = List.of(doc("a"), doc("b"));

        List<Document> result = r.rerank("q", docs, 6);

        assertThat(result).isSameAs(docs); // mesma instância, sem reranking
    }

    @Test
    @DisplayName("prefere candidatos relevantes à query")
    void prefersRelevant() {
        Map<String, float[]> embeddings = new HashMap<>();
        embeddings.put("q", new float[]{1, 0, 0});
        embeddings.put("relevante", new float[]{1, 0, 0});   // cosine=1 com q
        embeddings.put("irrelevante1", new float[]{0, 1, 0}); // cosine=0
        embeddings.put("irrelevante2", new float[]{0, 0, 1}); // cosine=0
        MmrReranker r = new MmrReranker(new StubEmbeddingModel(embeddings));

        List<Document> result = r.rerank("q",
                List.of(doc("irrelevante1"), doc("irrelevante2"), doc("relevante")),
                1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("relevante");
    }

    @Test
    @DisplayName("diversifica quando candidatos têm relevância parecida (λ baixo)")
    void diversifiesSimilarCandidates() {
        Map<String, float[]> embeddings = new HashMap<>();
        embeddings.put("q", new float[]{1, 0, 0});
        embeddings.put("a", new float[]{0.9f, 0.1f, 0});      // alta rel
        embeddings.put("a-dup", new float[]{0.9f, 0.1f, 0});  // idêntico a "a"
        embeddings.put("b", new float[]{0.7f, 0, 0.7f});       // rel média, distinto

        // λ=0.3 dá peso forte à diversidade — penaliza candidato idêntico.
        MmrReranker r = new MmrReranker(new StubEmbeddingModel(embeddings), 0.3);

        List<Document> result = r.rerank("q",
                List.of(doc("a"), doc("a-dup"), doc("b")), 2);

        // Primeiro: o mais relevante ("a"). Segundo: deve preferir "b"
        // a "a-dup" porque a-dup é cópia perfeita do já selecionado.
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContent()).isEqualTo("a");
        assertThat(result.get(1).getContent()).isEqualTo("b");
    }

    @Test
    @DisplayName("se o embed da query falhar, devolve top-N na ordem original")
    void embeddingFailureFallbacksToOriginalOrder() {
        MmrReranker r = new MmrReranker(new FailingEmbeddingModel());
        List<Document> docs = List.of(doc("x"), doc("y"), doc("z"));

        List<Document> result = r.rerank("q", docs, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContent()).isEqualTo("x");
        assertThat(result.get(1).getContent()).isEqualTo("y");
    }

    private static Document doc(String content) {
        return new Document(content);
    }

    /** Stub determinístico: mapa de texto → vetor. */
    static class StubEmbeddingModel implements EmbeddingModel {
        private final Map<String, float[]> embeddings;

        StubEmbeddingModel(Map<String, float[]> embeddings) {
            this.embeddings = embeddings;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            String input = request.getInstructions().get(0);
            float[] vec = embeddings.getOrDefault(input, new float[]{0, 0, 0});
            return new EmbeddingResponse(List.of(new Embedding(vec, 0)));
        }

        @Override
        public float[] embed(Document doc) {
            return embeddings.getOrDefault(doc.getContent(), new float[]{0, 0, 0});
        }

        @Override
        public int dimensions() { return 3; }
    }

    static class FailingEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new RuntimeException("embedding offline");
        }

        @Override
        public float[] embed(Document doc) {
            throw new RuntimeException("embedding offline");
        }

        @Override
        public int dimensions() { return 768; }
    }
}
