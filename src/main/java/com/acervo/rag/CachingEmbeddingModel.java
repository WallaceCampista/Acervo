package com.acervo.rag;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Wrapper que cacheia chamadas de embedding com <strong>uma única entrada</strong>
 * — tipicamente queries do usuário durante o similaritySearch. Indexação em lote
 * passa direto pro {@link LocalEmbeddingModel} (chunks são únicos, cachear não
 * vale a pena).
 *
 * <p>TTL de 1 hora e capacidade de 1000 entradas — quando o usuário repete uma
 * pergunta (ou refina ligeiramente), economiza um round-trip pro LM Studio.
 */
@Component
@Primary
@Slf4j
public class CachingEmbeddingModel implements EmbeddingModel {

    private static final int MAX_ENTRIES = 1000;
    private static final Duration TTL = Duration.ofHours(1);

    private final LocalEmbeddingModel delegate;
    private final Cache<String, float[]> cache;

    public CachingEmbeddingModel(LocalEmbeddingModel delegate) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(TTL)
                .recordStats()
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        // Só cacheia chamadas single-input (queries). Batch vai direto.
        if (inputs == null || inputs.size() != 1) {
            return delegate.call(request);
        }
        String key = inputs.get(0);
        float[] cached = cache.getIfPresent(key);
        if (cached != null) {
            log.debug("cache hit ({} chars)", key.length());
            return new EmbeddingResponse(List.of(new Embedding(cached, 0)));
        }
        EmbeddingResponse fresh = delegate.call(request);
        if (!fresh.getResults().isEmpty()) {
            cache.put(key, fresh.getResults().get(0).getOutput());
        }
        return fresh;
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        // embed(Document) é usado pelo VectorStore para indexar chunks novos —
        // não vale a pena cachear. Delega direto.
        return delegate.embed(document);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    /** Útil em testes pra inspecionar hit rate. */
    public long size() {
        return cache.estimatedSize();
    }

    public long hitCount() {
        return cache.stats().hitCount();
    }

    public long missCount() {
        return cache.stats().missCount();
    }
}
