package com.acervo.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes unitários do CachingEmbeddingModel — não precisa de Spring context.
 * Usa um stub do {@link LocalEmbeddingModel} que conta invocações em vez do
 * Mockito (mais leve e robusto a Java 25).
 */
class CachingEmbeddingModelTest {

    private CountingDelegate delegate;
    private CachingEmbeddingModel cache;

    @BeforeEach
    void setUp() {
        delegate = new CountingDelegate();
        cache = new CachingEmbeddingModel(delegate);
    }

    @Test
    @DisplayName("primeira chamada com mesmo input é miss; segunda é hit")
    void singleInputCachesAcrossCalls() {
        EmbeddingRequest req = new EmbeddingRequest(List.of("o que é rasterização?"), null);

        cache.call(req);
        cache.call(req);

        assertThat(delegate.calls.get()).isEqualTo(1);
        assertThat(cache.hitCount()).isEqualTo(1);
        assertThat(cache.missCount()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("queries diferentes geram entradas separadas")
    void differentQueriesAreSeparateEntries() {
        cache.call(new EmbeddingRequest(List.of("query A"), null));
        cache.call(new EmbeddingRequest(List.of("query B"), null));
        cache.call(new EmbeddingRequest(List.of("query A"), null));

        // 2 entradas únicas, 1 hit na repetição de A
        assertThat(delegate.calls.get()).isEqualTo(2);
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.hitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("chamada batch (>1 input) NÃO é cacheada — passa direto")
    void batchBypassesCache() {
        EmbeddingRequest batch = new EmbeddingRequest(
                List.of("chunk 1", "chunk 2", "chunk 3"), null);

        cache.call(batch);
        cache.call(batch);

        // Batch sempre delega; nada salvo no cache
        assertThat(delegate.calls.get()).isEqualTo(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("embed(Document) passa direto pro delegate (indexação não cacheia)")
    void embedDocumentBypassesCache() {
        cache.embed(new Document("conteúdo do chunk"));
        cache.embed(new Document("conteúdo do chunk"));

        assertThat(delegate.embedCalls.get()).isEqualTo(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("dimensions() delega")
    void dimensionsDelegates() {
        assertThat(cache.dimensions()).isEqualTo(768);
    }

    /** Stub manual — Mockito + Byte Buddy + Java 25 às vezes briga. */
    static class CountingDelegate extends LocalEmbeddingModel {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger embedCalls = new AtomicInteger();

        CountingDelegate() {
            super("http://localhost:1234", "test-key", "test-model", 768);
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.incrementAndGet();
            // Retorna um embedding fake do tamanho certo
            float[] fake = new float[768];
            return new EmbeddingResponse(List.of(new Embedding(fake, 0)));
        }

        @Override
        public float[] embed(Document document) {
            embedCalls.incrementAndGet();
            return new float[768];
        }
    }
}
