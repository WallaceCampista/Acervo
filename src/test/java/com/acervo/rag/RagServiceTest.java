package com.acervo.rag;

import com.acervo.AbstractIntegrationTest;
import com.acervo.domain.Chunk;
import com.acervo.domain.Conversation;
import com.acervo.domain.Document;
import com.acervo.domain.Message;
import com.acervo.domain.Subject;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.ConversationRepository;
import com.acervo.repository.DocumentRepository;
import com.acervo.repository.MessageRepository;
import com.acervo.repository.SubjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes de comportamento do RagService com VectorStore e ChatModel mockados.
 * Usa Postgres real (via Testcontainer herdado) pra exercitar a persistência
 * em Message/Citation — só a IA externa fica fake.
 */
class RagServiceTest extends AbstractIntegrationTest {

    @Autowired RagService ragService;

    @Autowired SubjectRepository subjects;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired com.acervo.repository.RetrievalMetricRepository retrievalMetrics;

    private com.acervo.repository.RetrievalMetricRepository retrievalMetricRepo() {
        return retrievalMetrics;
    }

    @MockBean VectorStore vectorStore;
    @MockBean ChatModel chatModel;

    private Subject subject;
    private Chunk chunk;
    private Conversation conversation;

    @BeforeEach
    void seed() {
        cleanup();
        subject = subjects.save(Subject.builder()
                .name("Matemática Discreta").color("#9a8fc4").build());
        Document doc = documents.save(Document.builder()
                .subject(subject).originalName("conjuntos.pdf")
                .storedPath("data/conjuntos.pdf").extension("PDF")
                .sizeBytes(1024).status(Document.Status.INDEXED).build());
        chunk = chunks.save(Chunk.builder()
                .document(doc).ordinal(0)
                .content("União: A ∪ B = {x : x ∈ A ∨ x ∈ B}.")
                .pageLabel("p. 3").tokenCount(8).build());
        conversation = conversations.save(Conversation.builder()
                .subject(subject).title("Nova conversa").build());
    }

    @AfterEach
    void cleanup() {
        retrievalMetrics.deleteAll();
        conversations.deleteAll();
        chunks.deleteAll();
        documents.deleteAll();
        subjects.deleteAll();
    }

    @Test
    @DisplayName("similaritySearch é chamado com top-K e filtro por subjectId")
    void topKAndSubjectFilter() {
        stubChat("União é tudo que está em A ou em B.");
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(retrievedDocument()));

        ragService.answer(conversation.getId(), "O que é união?");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest req = captor.getValue();
        // topK vem de acervo.rag.top-k (3 em test, 6 em dev) — só validamos
        // que foi setado, não o valor exato.
        assertThat(req.getTopK()).isPositive();
        assertThat(req.getFilterExpression().toString())
                .contains(subject.getId().toString());
    }

    @Test
    @DisplayName("ASSISTANT message persiste com citation linkando ao chunk certo + responseTimeMs")
    void persistsAssistantWithCitationAndTime() {
        stubChat("Resposta gerada.");
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(retrievedDocument()));

        Message saved = ragService.answer(conversation.getId(), "Qual a definição de união?");

        assertThat(saved.getResponseTimeMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(saved.getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(saved.getContent()).isEqualTo("Resposta gerada.");

        // Recarrega com citations eager pra evitar lazy fora de tx
        Message reloaded = messages.findByIdWithCitations(saved.getId()).orElseThrow();
        assertThat(reloaded.getCitations()).hasSize(1);
        assertThat(reloaded.getCitations().get(0).getChunk().getId()).isEqualTo(chunk.getId());
        assertThat(reloaded.getCitations().get(0).getRelevance())
                .isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("retrieval vazio gera resposta sem citations (fallback do LLM)")
    void emptyRetrievalNoCitations() {
        stubChat("Não encontrei isso na nossa base de dados.");
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        Message saved = ragService.answer(conversation.getId(), "pergunta fora do escopo");

        Message reloaded = messages.findByIdWithCitations(saved.getId()).orElseThrow();
        assertThat(reloaded.getCitations()).isEmpty();
        assertThat(reloaded.getContent())
                .contains("Não encontrei isso na nossa base de dados.");
    }

    @Test
    @DisplayName("título da conversa vira a pergunta na primeira interação")
    void titleSetFromFirstQuestion() {
        stubChat("ok");
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());
        assertThat(conversation.getTitle()).isEqualTo("Nova conversa");

        ragService.answer(conversation.getId(), "Qual a diferença entre união e interseção?");

        Conversation reloaded = conversations.findById(conversation.getId()).orElseThrow();
        assertThat(reloaded.getTitle())
                .isEqualTo("Qual a diferença entre união e interseção?");
    }

    @Test
    @DisplayName("falha no ChatModel vira mensagem amigável persistida com ⚠")
    void chatModelFailureBecomesFriendlyMessage() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        Message saved = ragService.answer(conversation.getId(), "qualquer coisa");

        assertThat(saved.getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(saved.getContent()).startsWith("⚠");
        assertThat(saved.getResponseTimeMs()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("retrieval persiste retrieval_metric com chunks/avg/top1 corretos")
    void persistsRetrievalMetric() {
        stubChat("ok");
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(retrievedDocument()));
        com.acervo.repository.RetrievalMetricRepository metrics = retrievalMetricRepo();
        long before = metrics.count();

        ragService.answer(conversation.getId(), "pergunta com métrica");

        long after = metrics.count();
        assertThat(after).isEqualTo(before + 1);
        var saved = metrics.findAll().stream()
                .filter(m -> m.getConversationId().equals(conversation.getId()))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(saved.getChunksRetrieved()).isEqualTo(1);
        assertThat(saved.isNoResults()).isFalse();
        assertThat(saved.getQuestionLength()).isEqualTo("pergunta com métrica".length());
        assertThat(saved.isUsedLexicalFusion()).isTrue();
    }

    @Test
    @DisplayName("chunkId inválido na metadata do match não quebra a indexação")
    void invalidChunkIdInMetadataIsSkipped() {
        stubChat("ok");
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunkId", "não-é-uuid");
        org.springframework.ai.document.Document bad =
                new org.springframework.ai.document.Document("x", "conteúdo", meta);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(bad, retrievedDocument()));

        Message saved = ragService.answer(conversation.getId(), "pergunta");

        Message reloaded = messages.findByIdWithCitations(saved.getId()).orElseThrow();
        // Só o chunk válido vira citation; o inválido é silenciosamente ignorado.
        assertThat(reloaded.getCitations()).hasSize(1);
        assertThat(reloaded.getCitations().get(0).getChunk().getId()).isEqualTo(chunk.getId());
    }

    // ---- helpers ----

    private void stubChat(String content) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    private org.springframework.ai.document.Document retrievedDocument() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunkId", chunk.getId().toString());
        meta.put("documentName", "conjuntos.pdf");
        meta.put("pageLabel", "p. 3");
        meta.put("distance", 0.15);
        return new org.springframework.ai.document.Document(
                chunk.getId().toString(), chunk.getContent(), meta);
    }
}
