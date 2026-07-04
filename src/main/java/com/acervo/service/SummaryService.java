package com.acervo.service;

import com.acervo.ai.AiGenerationService;
import com.acervo.domain.Chunk;
import com.acervo.domain.Document;
import com.acervo.domain.DocumentSummary;
import com.acervo.rag.AiFailureTranslator;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.DocumentRepository;
import com.acervo.repository.DocumentSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SummaryService {

    private static final String SYSTEM_PROMPT = """
            Você é o Acervo, um assistente de estudo. Resuma o documento abaixo
            em português do Brasil, de forma clara e fiel ao conteúdo. Não invente
            informações: use APENAS o texto fornecido.
            """;

    // Orçamento de tokens do conteúdo enviado ao LLM. O contexto do modelo
    // local é limitado (Gemma 2 = 8192 tokens) e documentos longos estouravam
    // o contexto no resumo (erro "n_keep >= n_ctx"). ~6000 tokens de conteúdo
    // deixam folga pro system prompt e pra geração do resumo. ATENÇÃO: docs
    // maiores que isso têm só o início resumido — para cobertura total seria
    // preciso resumo hierárquico (map-reduce), com mais chamadas ao LLM.
    private static final int MAX_BODY_TOKENS = 6_000;

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final DocumentSummaryRepository summaries;
    private final AiGenerationService ai;
    private final AiFailureTranslator failureTranslator;

    // Self-reference pra que chamadas a métodos @Transactional a partir de
    // callbacks do streaming (que rodam fora do contexto de transação)
    // passem pelo proxy do Spring — caso contrário a anotação é ignorada.
    @Autowired @Lazy
    private SummaryService self;

    public List<DocumentSummary> listForDocument(UUID documentId) {
        return summaries.findByDocument_Id(documentId);
    }

    public Optional<DocumentSummary> find(UUID documentId, DocumentSummary.Level level) {
        return summaries.findByDocument_IdAndLevel(documentId, level);
    }

    /**
     * Gera (ou regenera) o resumo no nível pedido para o documento. Persiste
     * substituindo qualquer resumo prévio do mesmo (documento, nível).
     */
    @Transactional
    public DocumentSummary generate(UUID documentId, DocumentSummary.Level level) {
        Document doc = documents.findById(documentId).orElseThrow();
        List<Chunk> docChunks = chunks.findByDocument_Id(documentId);
        if (docChunks.isEmpty()) {
            throw new IllegalStateException("Documento ainda não foi indexado.");
        }
        List<Chunk> budgeted = withinBudget(docChunks);
        String body = buildBody(budgeted);
        String userPrompt = userPromptFor(level, doc.getOriginalName(), body);
        String generated = ai.generate(SYSTEM_PROMPT, userPrompt);

        DocumentSummary entity = summaries.findByDocument_IdAndLevel(documentId, level)
                .orElseGet(() -> DocumentSummary.builder()
                        .document(doc).level(level).build());
        entity.setContent(generated);
        entity.setGeneratedAt(OffsetDateTime.now());
        return summaries.save(entity);
    }

    // Limita os chunks ao orçamento de tokens pra não estourar a janela de
    // contexto do modelo. Corta no limite de chunk (não no meio do texto) e
    // preserva a ordem do documento — então o resumo cobre o início do doc.
    // Docs muito longos perdem a parte final (ver nota em MAX_BODY_TOKENS).
    private List<Chunk> withinBudget(List<Chunk> docChunks) {
        List<Chunk> out = new ArrayList<>();
        int tokens = 0;
        for (Chunk c : docChunks) {
            if (tokens >= MAX_BODY_TOKENS) break;
            out.add(c);
            tokens += c.getTokenCount();
        }
        return out;
    }

    private String buildBody(List<Chunk> docChunks) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : docChunks) {
            if (c.getPageLabel() != null && !c.getPageLabel().isBlank()) {
                sb.append("[").append(c.getPageLabel()).append("] ");
            }
            sb.append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Versão em streaming — envia tokens via SSE conforme o LLM gera. Roda
     * em thread async pra liberar a thread do Tomcat logo após o handshake.
     *
     * <p>Eventos:
     * <ul>
     *   <li>{@code info} — JSON com {@code promptTokens} estimados (front
     *       usa pra estimar o tempo do prompt processing do LM Studio)</li>
     *   <li>{@code token} — cada delta de token do modelo</li>
     *   <li>{@code done} — fim do streaming, summary persistido</li>
     *   <li>{@code error} — falha (mensagem amigável)</li>
     * </ul>
     */
    @Async
    public void streamGenerate(UUID documentId, DocumentSummary.Level level, SseEmitter emitter) {
        Document doc;
        List<Chunk> docChunks;
        try {
            doc = documents.findById(documentId).orElseThrow();
            docChunks = chunks.findByDocument_Id(documentId);
        } catch (Exception e) {
            sendEvent(emitter, "error", "Documento não encontrado.");
            emitter.complete();
            return;
        }
        if (docChunks.isEmpty()) {
            sendEvent(emitter, "error", "Documento ainda não foi indexado.");
            emitter.complete();
            return;
        }

        List<Chunk> budgeted = withinBudget(docChunks);
        String body = buildBody(budgeted);
        String userPrompt = userPromptFor(level, doc.getOriginalName(), body);
        int promptTokens = budgeted.stream().mapToInt(Chunk::getTokenCount).sum();
        sendEvent(emitter, "info",
                "{\"promptTokens\":" + promptTokens + ",\"chunks\":" + docChunks.size() + "}");

        StringBuilder accumulator = new StringBuilder();
        try {
            ai.streamGenerate(SYSTEM_PROMPT, userPrompt)
                    .doOnNext(token -> {
                        if (token == null || token.isEmpty()) return;
                        accumulator.append(token);
                        // Percent-encode pra preservar espaços líderes: o
                        // parser SSE remove um espaço logo após `data:`, o
                        // que faria tokens BPE (" palavra") chegarem grudados
                        // no cliente. URLEncoder.encode usa form-encoding
                        // (espaço → '+'), mas decodeURIComponent no browser
                        // espera percent-encoding — então normalizamos.
                        sendEvent(emitter, "token",
                                URLEncoder.encode(token, StandardCharsets.UTF_8)
                                          .replace("+", "%20"));
                    })
                    .blockLast();

            self.persistSummary(documentId, level, accumulator.toString());
            sendEvent(emitter, "done", "");
            emitter.complete();
        } catch (Exception e) {
            String friendly = failureTranslator.translate(e);
            log.warn("Falha em streaming de resumo doc={} level={} ({}): {}",
                    documentId, level, e.getClass().getSimpleName(), friendly);
            sendEvent(emitter, "error", friendly);
            emitter.complete();
        }
    }

    @Transactional
    public void persistSummary(UUID documentId, DocumentSummary.Level level, String content) {
        Document doc = documents.findById(documentId).orElseThrow();
        DocumentSummary entity = summaries.findByDocument_IdAndLevel(documentId, level)
                .orElseGet(() -> DocumentSummary.builder()
                        .document(doc).level(level).build());
        entity.setContent(content);
        entity.setGeneratedAt(OffsetDateTime.now());
        summaries.save(entity);
    }

    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data == null ? "" : data));
        } catch (IOException | IllegalStateException e) {
            // Cliente desconectou — ignorado.
            log.debug("Falha ao enviar SSE '{}': {}", name, e.getMessage());
        }
    }

    private String userPromptFor(DocumentSummary.Level level, String docName, String body) {
        return switch (level) {
            case PARAGRAFO -> """
                Documento: %s

                Resuma o documento em UM ÚNICO PARÁGRAFO de no máximo 5 linhas.
                Capture a ideia central, sem listas ou marcadores.

                Conteúdo:
                %s
                """.formatted(docName, body);
            case PAGINA -> """
                Documento: %s

                Resuma o documento em aproximadamente UMA PÁGINA (12 a 20 linhas).
                Use parágrafos curtos. Cite seções/capítulos do material quando
                fizer sentido.

                Conteúdo:
                %s
                """.formatted(docName, body);
            case MAPA_MENTAL -> """
                Documento: %s

                Gere um MAPA MENTAL TEXTUAL hierárquico do documento. Use o formato:
                - Tópico principal
                  - Subtópico
                    - Detalhe
                Não exceda 4 níveis de profundidade. Mantenha conciso.

                Conteúdo:
                %s
                """.formatted(docName, body);
        };
    }
}
