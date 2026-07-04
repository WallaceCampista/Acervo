package com.acervo.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reranker baseado em <strong>Maximum Marginal Relevance</strong> (MMR).
 * Algoritmo clássico de diversificação: re-ordena os candidatos balanceando
 * <em>relevância</em> à pergunta com <em>diversidade</em> entre os já escolhidos.
 *
 * <p>Reduz redundância (vários chunks dizendo a mesma coisa) sem precisar de
 * modelo extra rodando — calcula cosine similarity dos embeddings que já temos.
 *
 * <p>Fórmula:
 * <pre>
 *   MMR(d) = λ · sim(d, query) − (1 − λ) · max sim(d, d') for d' in selecionados
 * </pre>
 * λ ∈ [0,1]: 1 = só relevância (sem reranking), 0 = pura diversidade.
 * Default {@code λ = 0.7} (peso forte na relevância, leve push pra diversidade).
 */
@Component
public class MmrReranker {

    private final EmbeddingModel embeddingModel;
    private final double lambda;

    @Autowired
    public MmrReranker(EmbeddingModel embeddingModel,
                       @Value("${acervo.rag.mmr-lambda:0.7}") double lambda) {
        this.embeddingModel = embeddingModel;
        this.lambda = lambda;
    }

    /** Construtor pra testes — permite ajustar lambda sem context Spring. */
    public MmrReranker(EmbeddingModel embeddingModel) {
        this(embeddingModel, 0.7);
    }

    /**
     * Reranqueia até {@code topN} documentos. Se {@code candidates.size() <= topN}
     * já retorna na ordem original — não há ganho em reordenar quando não há
     * mais candidato que necessário.
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates == null || candidates.size() <= topN) {
            return candidates == null ? List.of() : candidates;
        }

        float[] queryVec;
        try {
            queryVec = embeddingModel.embed(new Document(query));
        } catch (Exception e) {
            // Sem embedding da query não dá pra rerank — devolve top-N na ordem
            // original (que veio do retrieval).
            return candidates.subList(0, topN);
        }

        // Embedding por candidato — usa cache via CachingEmbeddingModel
        // (cada chunk é único, então normalmente miss; mas barato vs ganho).
        float[][] vecs = new float[candidates.size()][];
        for (int i = 0; i < candidates.size(); i++) {
            try {
                vecs[i] = embeddingModel.embed(candidates.get(i));
            } catch (Exception e) {
                vecs[i] = null;
            }
        }

        List<Integer> selected = new ArrayList<>(topN);
        Set<Integer> remaining = new HashSet<>();
        for (int i = 0; i < candidates.size(); i++) remaining.add(i);

        while (selected.size() < topN && !remaining.isEmpty()) {
            int bestIdx = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i : remaining) {
                double relevance = vecs[i] != null ? cosine(queryVec, vecs[i]) : 0.0;
                double diversityPenalty = 0.0;
                for (int s : selected) {
                    if (vecs[i] == null || vecs[s] == null) continue;
                    diversityPenalty = Math.max(diversityPenalty, cosine(vecs[i], vecs[s]));
                }
                double mmr = lambda * relevance - (1 - lambda) * diversityPenalty;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) break;
            selected.add(bestIdx);
            remaining.remove(bestIdx);
        }

        List<Document> reranked = new ArrayList<>(selected.size());
        for (int i : selected) reranked.add(candidates.get(i));
        return reranked;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0 : dot / denom;
    }
}
