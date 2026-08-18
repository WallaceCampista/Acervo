package com.acervo.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Inspeção e avaliação do retrieval <strong>sem gerar resposta</strong>.
 *
 * <p>Existe por um motivo prático: a qualidade de um RAG é decidida no
 * retrieval, mas o sintoma (resposta errada) aparece na geração — o que
 * confunde o diagnóstico e custa uma chamada ao LLM a cada tentativa. Aqui dá
 * pra ver exatamente o que o modelo receberia, e medir isso contra um gabarito.
 *
 * <p>Chama o mesmo {@link RagService#inspect} que a resposta usa; não há
 * caminho paralelo que possa divergir do de produção.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalEvalService {

    /** Tamanho do trecho devolvido no preview — o mesmo usado nas citações. */
    private static final int EXCERPT_CHARS = 280;

    private final RagService ragService;

    // ---- DTOs ----

    /**
     * Um trecho recuperado e o porquê de ele estar ali.
     *
     * <p>{@code similarity} é {@code 1 - distance} e só existe para trechos que
     * vieram da perna vetorial — os que entraram apenas pela busca lexical não
     * têm distância de embedding, e nesse caso vem {@code null} em vez de um
     * número inventado.
     */
    public record RetrievedItem(int rank, UUID chunkId, String documentName, String pageLabel,
                                RagService.Source source, Double distance, Double similarity,
                                int approxTokens, String excerpt) {}

    /** O que o LLM receberia para esta pergunta. */
    public record RetrievalPreview(String question, int vectorHits, int lexicalHits,
                                   int fusedPool, int returned, int approxContextTokens,
                                   boolean wouldAbstain, long elapsedMs,
                                   List<RetrievedItem> items) {}

    /**
     * Um caso do gabarito. Vale o match por {@code expectedChunkIds} (preciso,
     * pegue os ids no {@code /preview}) ou por {@code expectedSnippets} (prático:
     * um pedaço do texto que a resposta certa precisa conter). Basta um dos dois
     * casar.
     */
    public record GoldenCase(String question, List<UUID> expectedChunkIds,
                             List<String> expectedSnippets) {}

    /** Resultado de um caso. {@code hitRank} é 1-based; 0 quando não houve hit. */
    public record CaseResult(String question, boolean hit, int hitRank, boolean abstained,
                             int returned, String error, List<RetrievedItem> items) {}

    /**
     * Agregado do gabarito.
     *
     * <p>{@code recallAtK} = fração de casos em que o trecho esperado apareceu
     * em qualquer posição do top-K. {@code mrr} = média de {@code 1/rank} do
     * primeiro acerto (pune o acerto que veio no fim da lista).
     * {@code abstentionRate} = fração em que o gate recusaria responder — é o
     * contrapeso: subir o piso de similaridade melhora precisão e piora isso.
     */
    public record EvalReport(int cases, int hits, double recallAtK, double mrr,
                             int abstentions, double abstentionRate, long elapsedMs,
                             List<CaseResult> results) {}

    // ---- API ----

    /**
     * Roda o retrieval e devolve o que seria enviado ao LLM. Somente leitura;
     * não cria conversa, mensagem nem métrica.
     */
    @Transactional(readOnly = true)
    public RetrievalPreview preview(UUID subjectId, String question) {
        long t0 = System.currentTimeMillis();
        RagService.Retrieved retrieved = ragService.inspect(subjectId, question);
        List<RetrievedItem> items = toItems(retrieved);
        int contextTokens = items.stream().mapToInt(RetrievedItem::approxTokens).sum();
        return new RetrievalPreview(question, retrieved.vectorHits(), retrieved.lexicalHits(),
                retrieved.fusedPool(), items.size(), contextTokens,
                items.isEmpty(), System.currentTimeMillis() - t0, items);
    }

    /**
     * Roda o gabarito inteiro e agrega recall@k / MRR / taxa de abstenção.
     * Falha de um caso não derruba a rodada — vira {@code error} no resultado
     * daquele caso, porque numa bateria de 50 perguntas perder tudo por causa
     * de uma é inútil.
     */
    @Transactional(readOnly = true)
    public EvalReport evaluate(UUID subjectId, List<GoldenCase> cases) {
        long t0 = System.currentTimeMillis();
        List<CaseResult> results = new ArrayList<>(cases.size());
        int hits = 0;
        int abstentions = 0;
        double reciprocalRankSum = 0;

        for (GoldenCase gc : cases) {
            CaseResult r = runCase(subjectId, gc);
            results.add(r);
            if (r.hit()) {
                hits++;
                reciprocalRankSum += 1.0 / r.hitRank();
            }
            if (r.abstained()) abstentions++;
        }

        int total = cases.size();
        return new EvalReport(total, hits,
                ratio(hits, total), ratio(reciprocalRankSum, total),
                abstentions, ratio(abstentions, total),
                System.currentTimeMillis() - t0, results);
    }

    // ---- internos ----

    private CaseResult runCase(UUID subjectId, GoldenCase gc) {
        try {
            RagService.Retrieved retrieved = ragService.inspect(subjectId, gc.question());
            List<Document> docs = retrieved.docs();
            List<RetrievedItem> items = toItems(retrieved);

            int hitRank = 0;
            for (int i = 0; i < docs.size(); i++) {
                if (matches(gc, items.get(i).chunkId(), docs.get(i).getContent())) {
                    hitRank = i + 1;
                    break;
                }
            }
            return new CaseResult(gc.question(), hitRank > 0, hitRank,
                    docs.isEmpty(), docs.size(), null, items);
        } catch (Exception e) {
            log.warn("Caso do gabarito falhou ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return new CaseResult(gc.question(), false, 0, false, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), List.of());
        }
    }

    private List<RetrievedItem> toItems(RagService.Retrieved retrieved) {
        Map<UUID, RagService.Source> sources = retrieved.sources();
        List<Document> docs = retrieved.docs();
        List<RetrievedItem> items = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            UUID chunkId = parseChunkId(d);
            Double distance = d.getMetadata().get("distance") instanceof Number n
                    ? n.doubleValue() : null;
            items.add(new RetrievedItem(
                    i + 1,
                    chunkId,
                    String.valueOf(d.getMetadata().getOrDefault("documentName", "")),
                    String.valueOf(d.getMetadata().getOrDefault("pageLabel", "")),
                    chunkId == null ? null : sources.get(chunkId),
                    distance,
                    distance == null ? null : 1.0 - distance,
                    approxTokens(d.getContent()),
                    truncate(d.getContent())));
        }
        return items;
    }

    private boolean matches(GoldenCase gc, UUID chunkId, String content) {
        if (chunkId != null && gc.expectedChunkIds() != null
                && gc.expectedChunkIds().contains(chunkId)) {
            return true;
        }
        if (gc.expectedSnippets() == null || content == null) return false;
        String haystack = normalize(content);
        return gc.expectedSnippets().stream()
                .filter(s -> s != null && !s.isBlank())
                .anyMatch(s -> haystack.contains(normalize(s)));
    }

    /**
     * Casefold + remove acento + colapsa espaço. Escrever gabarito não deve
     * exigir reproduzir a acentuação e as quebras de linha exatas que o
     * extrator de PDF cuspiu.
     */
    static String normalize(String s) {
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static UUID parseChunkId(Document d) {
        Object meta = d.getMetadata().get("chunkId");
        if (meta == null) return null;
        try {
            return UUID.fromString(meta.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Mesma regra de bolso do RagService (~4 chars/token). */
    private static int approxTokens(String s) {
        return s == null || s.isEmpty() ? 0 : s.length() / 4;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= EXCERPT_CHARS ? s : s.substring(0, EXCERPT_CHARS - 1) + "…";
    }

    private static double ratio(double numerator, int total) {
        return total == 0 ? 0.0 : numerator / total;
    }
}
