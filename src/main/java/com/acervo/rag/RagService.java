package com.acervo.rag;

import com.acervo.domain.Chunk;
import com.acervo.domain.Citation;
import com.acervo.domain.Conversation;
import com.acervo.domain.Message;
import com.acervo.domain.RetrievalMetric;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.ConversationRepository;
import com.acervo.repository.MessageRepository;
import com.acervo.repository.RetrievalMetricRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private static final String SYSTEM_PROMPT = """
        Você é o Acervo, um assistente de estudo. Responda em português do Brasil,
        de forma clara e didática, usando APENAS o contexto fornecido.
        Se a resposta não estiver no contexto, diga explicitamente:
        "Não encontrei isso na nossa base de dados."
        Quando citar, refira-se aos documentos pelo nome e à localização (página/slide).
        """;

    /**
     * Prompt usado quando o usuário anexa uma imagem. Diferente do RAG puro:
     * aqui a imagem é a fonte primária, então NÃO restringimos a resposta ao
     * contexto dos documentos (que pode até estar vazio). O contexto, quando
     * houver, entra como complemento.
     */
    private static final String VISION_SYSTEM_PROMPT = """
        Você é o Acervo, um assistente de estudo. O usuário anexou uma imagem.
        Analise a imagem com cuidado e responda SEMPRE em português do Brasil,
        de forma clara e didática. Se houver contexto de documentos abaixo,
        use-o para complementar a análise da imagem.
        """;

    /**
     * Resposta devolvida pelo gate de abstenção quando nenhum trecho passou no
     * filtro de relevância. Texto idêntico à frase instruída no
     * {@link #SYSTEM_PROMPT} — o usuário vê a mesma mensagem venha ela do gate
     * ou do modelo, mas pelo gate ela é <em>garantida</em>.
     */
    static final String NO_CONTEXT_ANSWER = "Não encontrei isso na nossa base de dados.";

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final ChunkRepository chunkRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final RetrievalMetricRepository retrievalMetricRepository;
    private final AiFailureTranslator failureTranslator;
    private final MmrReranker reranker;

    /**
     * Self-injection (lazy pra evitar dependência circular na construção).
     * Usado pra que chamadas internas a métodos @Transactional passem pelo
     * proxy do Spring — auto-chamadas dentro da mesma instância são
     * ignoradas pelo aspecto @Transactional.
     */
    @Autowired @Lazy
    private RagService self;

    @Value("${acervo.rag.top-k:6}")
    private int topK;

    @Value("${acervo.rag.min-chunks:3}")
    private int minChunks;

    @Value("${acervo.rag.max-context-tokens:6000}")
    private int maxContextTokens;

    /**
     * Similaridade mínima (cosseno, {@code 1 - distância}) pra um chunk entrar
     * no pool de candidatos. Sem esse piso o pgvector devolve sempre os k
     * vizinhos mais próximos por mais distantes que estejam — e o prompt
     * acabava rotulando lixo como "Contexto".
     */
    @Value("${acervo.rag.min-similarity:0.5}")
    private double minSimilarity;

    /** Piso de {@code ts_rank} na perna lexical. Ver ChunkRepository. */
    @Value("${acervo.rag.min-lexical-rank:0.01}")
    private double minLexicalRank;

    @Timed(value = "acervo.rag.answer", description = "Tempo de resposta síncrona do RAG")
    @Transactional
    public Message answer(UUID conversationId, String question) {
        return answer(conversationId, question, null, null);
    }

    /**
     * Resposta síncrona com imagem opcional anexada (vision). Quando a
     * {@code imageData} está presente, ela é anexada à {@link UserMessage}
     * como {@link Media} — o Spring AI converte para data-URL base64 e o
     * modelo multimodal (ex.: Gemma 3) a enxerga. Não há streaming neste
     * caminho: o upload chega via POST multipart, fora do canal SSE.
     */
    @Timed(value = "acervo.rag.answer", description = "Tempo de resposta síncrona do RAG")
    @Transactional
    public Message answer(UUID conversationId, String question,
                          byte[] imageData, String imageMime) {
        Conversation conv = conversationRepository.findById(conversationId).orElseThrow();
        long t0 = System.currentTimeMillis();
        saveUserMessageAndTitle(conv, question, imageData, imageMime);

        boolean hasImage = imageData != null && imageData.length > 0 && imageMime != null;
        try {
            // Sem pergunta textual não há o que buscar no acervo — a imagem
            // é a fonte. Com pergunta, mantém o retrieval híbrido normal.
            Retrieved retrieved = (question != null && !question.isBlank())
                    ? retrieveInternal(conv.getSubject().getId(), question)
                    : Retrieved.empty();
            List<Document> matches = retrieved.docs();
            recordRetrievalMetric(conv.getId(), question, retrieved);

            if (shouldAbstain(matches, hasImage)) {
                return persistAssistantWithCitations(conv, NO_CONTEXT_ANSWER, List.of(),
                        System.currentTimeMillis() - t0);
            }

            Prompt prompt = buildPrompt(matches, question, imageData, imageMime);
            String answer = chatModel.call(prompt).getResult().getOutput().getContent();
            return persistAssistantWithCitations(conv, answer, matches,
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return persistError(conv, e, System.currentTimeMillis() - t0);
        }
    }

    /**
     * Resposta em streaming via SSE. Roda em thread async (libera o request
     * thread do Tomcat assim que o handshake retorna). Envia cada delta de
     * token como evento `message`, e ao final envia `done` (sucesso) ou
     * `error` (falha) e completa o emitter.
     */
    @Timed(value = "acervo.rag.answer_stream", description = "Tempo total da resposta em streaming (do handshake ao completion)")
    @Async
    public void answerStream(UUID conversationId, String question, SseEmitter emitter) {
        long t0 = System.currentTimeMillis();
        try {
            Prepared prep = self.prepareRetrieval(conversationId, question);

            // Gate de abstenção: nada passou no filtro de relevância, então
            // não há o que transmitir. Streaming nunca carrega imagem (o
            // upload vai pelo POST multipart), logo hasImage é sempre false.
            if (shouldAbstain(prep.matches(), false)) {
                self.persistAssistantById(conversationId, NO_CONTEXT_ANSWER,
                        List.of(), System.currentTimeMillis() - t0);
                sendEvent(emitter, "message", encodeToken(NO_CONTEXT_ANSWER));
                sendEvent(emitter, "done", "");
                emitter.complete();
                return;
            }

            StringBuilder accumulator = new StringBuilder();

            chatModel.stream(prep.prompt())
                    .subscribe(
                            chunk -> {
                                String token = extractDelta(chunk);
                                if (token == null || token.isEmpty()) return;
                                accumulator.append(token);
                                sendEvent(emitter, "message", encodeToken(token));
                            },
                            error -> {
                                String friendly = failureTranslator.translate(error);
                                log.warn("Falha no streaming para conversa {} ({}): {}",
                                        conversationId, error.getClass().getSimpleName(), friendly);
                                self.persistErrorById(conversationId, friendly,
                                        System.currentTimeMillis() - t0);
                                sendEvent(emitter, "error", friendly);
                                emitter.complete();
                            },
                            () -> {
                                self.persistAssistantById(conversationId,
                                        accumulator.toString(), prep.matches(),
                                        System.currentTimeMillis() - t0);
                                sendEvent(emitter, "done", "");
                                emitter.complete();
                            });
        } catch (Exception e) {
            // Falha antes do streaming começar (conv não existe, retrieval explodiu).
            String friendly = failureTranslator.translate(e);
            log.warn("Falha antes do streaming para conversa {} ({}): {}",
                    conversationId, e.getClass().getSimpleName(), friendly);
            try {
                self.persistErrorById(conversationId, friendly,
                        System.currentTimeMillis() - t0);
            } catch (Exception persistEx) {
                log.error("Não foi possível persistir mensagem de erro", persistEx);
            }
            sendEvent(emitter, "error", friendly);
            emitter.complete();
        }
    }

    /**
     * Cria a USER message, atualiza título da conversa, faz o retrieval e
     * monta o prompt. Tudo em uma única transação (curta) que commita
     * ANTES do streaming começar — assim a pergunta já aparece persistida
     * mesmo que o LLM trave depois.
     */
    @Transactional
    public Prepared prepareRetrieval(UUID conversationId, String question) {
        Conversation conv = conversationRepository.findById(conversationId).orElseThrow();
        saveUserMessageAndTitle(conv, question);
        Retrieved retrieved = retrieveInternal(conv.getSubject().getId(), question);
        recordRetrievalMetric(conv.getId(), question, retrieved);
        Prompt prompt = buildPrompt(retrieved.docs(), question);
        return new Prepared(prompt, retrieved.docs());
    }

    /**
     * Gate de abstenção. Quando nenhum trecho passou no filtro de relevância,
     * chamar o LLM é contraproducente: ele responderia pelo conhecimento
     * paramétrico dele — que é justamente a alucinação que o RAG existe pra
     * evitar. Devolver a recusa aqui torna o comportamento determinístico em
     * vez de depender da boa vontade do modelo local.
     *
     * <p>Não se aplica quando há imagem anexada: nesse caminho a imagem é a
     * fonte primária e o contexto documental é só complemento opcional.
     */
    private boolean shouldAbstain(List<Document> matches, boolean hasImage) {
        return matches.isEmpty() && !hasImage;
    }

    /**
     * Percent-encode pra preservar espaços líderes (BPE) que o parser SSE
     * removeria. {@link URLEncoder} usa form-encoding (espaço → {@code '+'});
     * normalizamos pra percent-encoding por causa do {@code decodeURIComponent}
     * no browser.
     */
    private static String encodeToken(String token) {
        return URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Transactional
    public void persistAssistantById(UUID conversationId, String content,
                                     List<Document> matches, long elapsedMs) {
        Conversation conv = conversationRepository.findById(conversationId).orElseThrow();
        persistAssistantWithCitations(conv, content, matches, elapsedMs);
    }

    @Transactional
    public void persistErrorById(UUID conversationId, String friendly, long elapsedMs) {
        Conversation conv = conversationRepository.findById(conversationId).orElseThrow();
        Message errorMsg = Message.builder()
                .conversation(conv)
                .role(Message.Role.ASSISTANT)
                .content("⚠ " + friendly)
                .responseTimeMs(elapsedMs)
                .build();
        messageRepository.save(errorMsg);
    }

    // ---- helpers privados (todos chamados dentro de uma transação) ----

    private void saveUserMessageAndTitle(Conversation conv, String question) {
        saveUserMessageAndTitle(conv, question, null, null);
    }

    private void saveUserMessageAndTitle(Conversation conv, String question,
                                         byte[] imageData, String imageMime) {
        boolean hasText = question != null && !question.isBlank();
        // content é NOT NULL no schema; mensagem só-imagem ganha um rótulo.
        String content = hasText ? question : (imageData != null ? "📷 Imagem" : "");
        Message userMsg = Message.builder()
                .conversation(conv)
                .role(Message.Role.USER)
                .content(content)
                .imageData(imageData)
                .imageMime(imageMime)
                .build();
        messageRepository.save(userMsg);
        if ("Nova conversa".equals(conv.getTitle())) {
            conv.setTitle(truncate(hasText ? question : "Imagem", 60));
        }
    }

    /** Constante k do Reciprocal Rank Fusion. 60 é o default da literatura. */
    private static final int RRF_K = 60;

    /** Origem de um chunk no retrieval híbrido — só pra diagnóstico. */
    public enum Source { VECTOR, LEXICAL, BOTH }

    /**
     * Resultado do retrieval com os contadores de cada perna. Os contadores
     * existem pra que a inspeção (retrieval-only) e a métrica saibam *por que*
     * o resultado ficou como ficou, sem ter que rodar a busca de novo.
     */
    public record Retrieved(List<Document> docs, int vectorHits, int lexicalHits,
                            int fusedPool, Map<UUID, Source> sources) {

        public static Retrieved empty() {
            return new Retrieved(List.of(), 0, 0, 0, Map.of());
        }
    }

    /**
     * Retrieval híbrido, exposto pra inspeção sem geração — é exatamente o
     * mesmo caminho que a resposta usa, então o que se vê aqui é o que o LLM
     * receberia. Ver {@code RetrievalEvalService}.
     */
    public Retrieved inspect(UUID subjectId, String question) {
        return retrieveInternal(subjectId, question);
    }

    private List<Document> retrieve(UUID subjectId, String question) {
        return retrieveInternal(subjectId, question).docs();
    }

    /**
     * Hybrid retrieval: combina busca vetorial e busca lexical (Postgres FTS)
     * via Reciprocal Rank Fusion. Pesca {@code topK * 3} candidatos de cada
     * lado e funde pelo score combinado, retornando os melhores {@code topK}.
     *
     * <p>Resolve o caso onde a busca vetorial perde por excesso de paráfrase —
     * "o que diz a página 14?" depende mais de match exato que de semântica.
     *
     * <p><strong>O corte por relevância acontece nas duas pernas, antes do
     * RRF.</strong> Depois da fusão o score é rank-based ({@code 1/(k+rank)}),
     * sem relação com similaridade — filtrar ali seria filtrar por posição, não
     * por qualidade. Consequência desejada: quando nada é relevante o pool sai
     * vazio, e é isso que arma o gate de abstenção.
     */
    private Retrieved retrieveInternal(UUID subjectId, String question) {
        // Pega bastante candidato pra dar margem ao reranker MMR diversificar.
        int candidates = Math.max(topK * 3, 12);

        List<Document> vectorMatches = vectorStore.similaritySearch(
                SearchRequest.query(question)
                        .withTopK(candidates)
                        .withSimilarityThreshold(minSimilarity)
                        .withFilterExpression("subjectId == '" + subjectId + "'"));
        List<UUID> lexicalIds = safeLexicalSearch(subjectId, question, candidates);

        // Score RRF por chunkId + dicionário pra recuperar o Document
        Map<UUID, Double> scores = new HashMap<>();
        Map<UUID, Document> byChunkId = new LinkedHashMap<>();
        Map<UUID, Source> sources = new HashMap<>();

        for (int i = 0; i < vectorMatches.size(); i++) {
            Document d = vectorMatches.get(i);
            UUID chunkId = parseChunkId(d);
            if (chunkId == null) continue;
            byChunkId.putIfAbsent(chunkId, d);
            sources.put(chunkId, Source.VECTOR);
            scores.merge(chunkId, 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < lexicalIds.size(); i++) {
            UUID chunkId = lexicalIds.get(i);
            scores.merge(chunkId, 1.0 / (RRF_K + i + 1), Double::sum);
            // Ausente → LEXICAL; já vindo do vetorial → BOTH.
            sources.merge(chunkId, Source.LEXICAL, (existing, incoming) -> Source.BOTH);
            if (!byChunkId.containsKey(chunkId)) {
                Document built = buildDocumentFromLexicalChunk(chunkId);
                if (built != null) byChunkId.put(chunkId, built);
            }
        }

        // Funde por score RRF, mantém o pool de candidatos
        List<Document> fused = scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .map(e -> byChunkId.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();

        // Reranker MMR diversifica e devolve os top-K finais
        List<Document> reranked = reranker.rerank(question, fused, topK);

        // Contexto adaptativo: corta antes de topK se passou do orçamento
        // de tokens — pergunta simples economiza, pergunta complexa pega tudo
        // até o limite. Mínimo de minChunks pra não sair pelado.
        return new Retrieved(cutByTokenBudget(reranked), vectorMatches.size(),
                lexicalIds.size(), fused.size(), sources);
    }

    /**
     * Trunca a lista pelo orçamento de tokens estimado (~4 chars/token, regra
     * de bolso). Sempre devolve no mínimo {@link #minChunks} entradas.
     */
    private List<Document> cutByTokenBudget(List<Document> docs) {
        if (docs.isEmpty()) return docs;
        int approxTokens = 0;
        for (int i = 0; i < docs.size(); i++) {
            int chunkTokens = estimateTokens(docs.get(i).getContent());
            if (i >= minChunks && approxTokens + chunkTokens > maxContextTokens) {
                return docs.subList(0, i);
            }
            approxTokens += chunkTokens;
        }
        return docs;
    }

    private static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        // Regra clássica: ~4 chars por token em texto natural (PT/EN).
        return s.length() / 4;
    }

    private List<UUID> safeLexicalSearch(UUID subjectId, String question, int limit) {
        try {
            return chunkRepository.findTopByLexicalSearch(
                    subjectId, question, minLexicalRank, limit);
        } catch (Exception e) {
            // Lexical é complementar — se falhar (query mal formada, etc),
            // segue só com o vetorial e loga em debug.
            log.debug("Lexical search falhou ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    private UUID parseChunkId(Document d) {
        Object meta = d.getMetadata().get("chunkId");
        if (meta == null) return null;
        try {
            return UUID.fromString(meta.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Document buildDocumentFromLexicalChunk(UUID chunkId) {
        return chunkRepository.findById(chunkId).map(chunk -> {
            Map<String, Object> meta = new HashMap<>();
            meta.put("chunkId", chunkId.toString());
            meta.put("documentName", chunk.getDocument().getOriginalName());
            meta.put("pageLabel", chunk.getPageLabel());
            meta.put("source", "lexical");
            return new Document(chunkId.toString(), chunk.getContent(), meta);
        }).orElse(null);
    }

    /**
     * Persiste um snapshot do retrieval pra análise posterior. Falhas aqui
     * são engolidas pra não atrapalhar o fluxo principal de resposta.
     *
     * <p>{@code usedLexicalFusion} reflete se a perna lexical de fato trouxe
     * candidato — antes era hard-coded por caminho de código (true no síncrono,
     * false no streaming), o que media qual método rodou em vez de medir a
     * fusão. Como os dois caminhos chamam o mesmo retrieval, o campo não
     * distinguia nada.
     */
    private void recordRetrievalMetric(UUID conversationId, String question,
                                       Retrieved retrieved) {
        try {
            List<Document> matches = retrieved.docs();
            boolean usedLexicalFusion = retrieved.lexicalHits() > 0;
            int size = matches.size();
            Double avg = null;
            Double top1 = null;
            if (size > 0) {
                double sum = 0;
                int counted = 0;
                Double firstDistance = null;
                for (Document d : matches) {
                    Object dist = d.getMetadata().get("distance");
                    if (dist instanceof Number n) {
                        if (firstDistance == null) firstDistance = n.doubleValue();
                        sum += n.doubleValue();
                        counted++;
                    }
                }
                if (counted > 0) avg = sum / counted;
                top1 = firstDistance;
            }
            retrievalMetricRepository.save(RetrievalMetric.builder()
                    .conversationId(conversationId)
                    .questionLength(question != null ? question.length() : 0)
                    .chunksRetrieved(size)
                    .avgDistance(avg)
                    .top1Distance(top1)
                    .noResults(size == 0)
                    .usedLexicalFusion(usedLexicalFusion)
                    .build());
        } catch (Exception e) {
            log.warn("Falha ao registrar retrieval_metric (não-bloqueante): {}", e.getMessage());
        }
    }

    private Prompt buildPrompt(List<Document> matches, String question) {
        return buildPrompt(matches, question, null, null);
    }

    private Prompt buildPrompt(List<Document> matches, String question,
                               byte[] imageData, String imageMime) {
        boolean hasImage = imageData != null && imageData.length > 0 && imageMime != null;

        String text;
        if (!matches.isEmpty()) {
            text = "Contexto:\n" + buildContext(matches)
                    + "\n\nPergunta: " + (question == null ? "" : question);
        } else if (question != null && !question.isBlank()) {
            text = question;
        } else {
            text = "Descreva e analise a imagem anexada.";
        }

        UserMessage userMessage;
        if (hasImage) {
            Media media = new Media(MimeType.valueOf(imageMime),
                    new ByteArrayResource(imageData));
            userMessage = new UserMessage(text, List.of(media));
        } else {
            userMessage = new UserMessage(text);
        }

        String systemPrompt = hasImage ? VISION_SYSTEM_PROMPT : SYSTEM_PROMPT;
        return new Prompt(List.of(new SystemMessage(systemPrompt), userMessage));
    }

    private Message persistAssistantWithCitations(Conversation conv, String content,
                                                  List<Document> matches, long elapsedMs) {
        Message assistantMsg = Message.builder()
                .conversation(conv)
                .role(Message.Role.ASSISTANT)
                .content(content)
                .responseTimeMs(elapsedMs)
                .build();
        messageRepository.save(assistantMsg);
        for (int i = 0; i < matches.size(); i++) {
            Document doc = matches.get(i);
            UUID chunkId = parseChunkId(doc);
            if (chunkId == null) continue;
            Chunk chunk = chunkRepository.findById(chunkId).orElse(null);
            if (chunk == null) continue;
            Citation c = Citation.builder()
                    .message(assistantMsg)
                    .chunk(chunk)
                    .relevance(relevance(doc, i, matches.size()))
                    .excerpt(truncate(chunk.getContent(), 280))
                    .build();
            assistantMsg.getCitations().add(c);
        }
        return messageRepository.save(assistantMsg);
    }

    private Message persistError(Conversation conv, Throwable e, long elapsedMs) {
        String friendly = failureTranslator.translate(e);
        log.warn("Resposta não gerada para conversa {} ({}): {}",
                conv.getId(), e.getClass().getSimpleName(), friendly);
        Message errorMsg = Message.builder()
                .conversation(conv)
                .role(Message.Role.ASSISTANT)
                .content("⚠ " + friendly)
                .responseTimeMs(elapsedMs)
                .build();
        return messageRepository.save(errorMsg);
    }

    private String extractDelta(org.springframework.ai.chat.model.ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null
                || chunk.getResult().getOutput() == null) return null;
        return chunk.getResult().getOutput().getContent();
    }

    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data == null ? "" : data));
        } catch (IOException | IllegalStateException e) {
            // Cliente desconectou ou emitter já encerrado — não vale a pena logar
            // em error; é cenário esperado quando o usuário fecha a aba.
            log.debug("Falha ao enviar evento SSE '{}': {}", name, e.getMessage());
        }
    }

    private String buildContext(List<Document> matches) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            Document d = matches.get(i);
            sb.append("[#").append(i + 1).append(" — ")
              .append(d.getMetadata().getOrDefault("documentName", "doc"))
              .append(" · ").append(d.getMetadata().getOrDefault("pageLabel", ""))
              .append("]\n")
              .append(d.getContent())
              .append("\n\n");
        }
        return sb.toString();
    }

    private double relevance(Document doc, int rank, int total) {
        Object score = doc.getMetadata().get("distance");
        if (score instanceof Number n) return Math.max(0, 1.0 - n.doubleValue());
        return Math.max(0.3, 1.0 - ((double) rank / total));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** DTO interno: resultado da fase de preparação + retrieval. */
    public record Prepared(Prompt prompt, List<Document> matches) {}
}
