package com.acervo.integration;

import com.acervo.AbstractIntegrationTest;
import com.acervo.config.AcervoUserDetails;
import com.acervo.domain.Chunk;
import com.acervo.domain.Conversation;
import com.acervo.domain.Document;
import com.acervo.domain.Subject;
import com.acervo.domain.User;
import com.acervo.repository.AuditLogRepository;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.ConversationRepository;
import com.acervo.repository.DocumentRepository;
import com.acervo.repository.MessageRepository;
import com.acervo.repository.SubjectRepository;
import com.acervo.repository.SubjectShareRepository;
import com.acervo.repository.UserRepository;
import com.acervo.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo end-to-end de chat. Roda com:
 * <ul>
 *   <li>VectorStore e ChatModel mockados (sem LM Studio).</li>
 *   <li>Usuário autenticado via {@code user(AcervoUserDetails)} do
 *       spring-security-test — substitui o antigo flag de sessão.</li>
 *   <li>CSRF token via {@code csrf()} pra POSTs.</li>
 * </ul>
 */
@AutoConfigureMockMvc
class ChatFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired SubjectRepository subjects;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired AuditLogRepository auditLogs;
    @Autowired SubjectShareRepository shares;

    @MockBean VectorStore vectorStore;
    @MockBean ChatModel chatModel;

    private User user;
    private Subject subject;
    private Chunk chunk;

    @BeforeEach
    void seed() {
        cleanup();
        user = userService.signup("aluno@acervo.dev", "senha123",
                "Aluno", "Teste", User.Role.ALUNO);
        subject = subjects.save(Subject.builder()
                .name("Computação Gráfica").color("#d8a657").owner(user).build());
        Document doc = documents.save(Document.builder()
                .subject(subject).originalName("aula01.pdf")
                .storedPath("data/aula01.pdf").extension("PDF")
                .sizeBytes(2048).status(Document.Status.INDEXED).build());
        chunk = chunks.save(Chunk.builder()
                .document(doc).ordinal(0)
                .content("Rasterização converte primitivas em pixels.")
                .pageLabel("p. 14").tokenCount(6).build());
    }

    @AfterEach
    void cleanup() {
        // Ordem importa: filhos antes de pais por causa das FKs.
        conversations.deleteAll();
        chunks.deleteAll();
        documents.deleteAll();
        shares.deleteAll();
        subjects.deleteAll();
        auditLogs.deleteAll();
        userRepository.deleteAll();
    }

    private AcervoUserDetails asPrincipal() {
        return new AcervoUserDetails(user);
    }

    @Test
    @DisplayName("envia pergunta → resposta persistida com citation e responseTimeMs")
    void fullFlow() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunkId", chunk.getId().toString());
        meta.put("documentName", "aula01.pdf");
        meta.put("pageLabel", "p. 14");
        meta.put("distance", 0.18);
        org.springframework.ai.document.Document retrieved =
                new org.springframework.ai.document.Document(
                        chunk.getId().toString(),
                        chunk.getContent(), meta);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(retrieved));

        String answerText = "Rasterização é o processo de converter "
                + "primitivas geométricas em pixels.";
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(
                        new AssistantMessage(answerText)))));

        var createResult = mockMvc.perform(post("/chat/conversations")
                        .with(user(asPrincipal())).with(csrf())
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/chat?subject=*&conv=*"))
                .andReturn();
        UUID conversationId = extractConvId(createResult.getResponse().getRedirectedUrl());

        mockMvc.perform(post("/chat/conversations/" + conversationId + "/messages")
                        .with(user(asPrincipal())).with(csrf())
                        .param("subjectId", subject.getId().toString())
                        .param("question", "O que é rasterização?"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat?subject=" + subject.getId()
                        + "&conv=" + conversationId));

        Conversation conv = conversations.findById(conversationId).orElseThrow();
        var conversationMessages = messages.findByConversation_IdOrderByCreatedAtAsc(conversationId);
        assertThat(conversationMessages).hasSize(2);

        var u = conversationMessages.get(0);
        var assistant = conversationMessages.get(1);
        assertThat(u.getContent()).isEqualTo("O que é rasterização?");
        assertThat(assistant.getContent()).isEqualTo(answerText);
        assertThat(assistant.getResponseTimeMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(assistant.getRole()).isEqualTo(com.acervo.domain.Message.Role.ASSISTANT);
        assertThat(conv.getTitle()).isEqualTo("O que é rasterização?");
    }

    @Test
    @DisplayName("acesso sem autenticação é bloqueado e redirecionado pra /landing")
    void sessionGuard() throws Exception {
        mockMvc.perform(get("/chat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/landing"));
    }

    @Test
    @DisplayName("erro do LLM vira mensagem amigável persistida (sem 500)")
    void llmFailureBecomesFriendlyMessage() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        var createResult = mockMvc.perform(post("/chat/conversations")
                        .with(user(asPrincipal())).with(csrf())
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        UUID conversationId = extractConvId(createResult.getResponse().getRedirectedUrl());

        mockMvc.perform(post("/chat/conversations/" + conversationId + "/messages")
                        .with(user(asPrincipal())).with(csrf())
                        .param("subjectId", subject.getId().toString())
                        .param("question", "qualquer coisa"))
                .andExpect(status().is3xxRedirection());

        var msgs = messages.findByConversation_IdOrderByCreatedAtAsc(conversationId);
        assertThat(msgs).hasSize(2);
        var assistant = msgs.get(1);
        assertThat(assistant.getRole().name()).isEqualTo("ASSISTANT");
        assertThat(assistant.getContent())
                .startsWith("⚠")
                .containsIgnoringCase("LM Studio");
    }

    @Test
    @DisplayName("/stream: SSE envia tokens, persiste resposta + citation + responseTimeMs")
    void streamingFlow() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunkId", chunk.getId().toString());
        meta.put("documentName", "aula01.pdf");
        meta.put("pageLabel", "p. 14");
        meta.put("distance", 0.22);
        org.springframework.ai.document.Document retrieved =
                new org.springframework.ai.document.Document(
                        chunk.getId().toString(),
                        chunk.getContent(), meta);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(retrieved));

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunkOf("Rasterização "),
                chunkOf("converte primitivas "),
                chunkOf("em pixels.")));

        var createResult = mockMvc.perform(post("/chat/conversations")
                        .with(user(asPrincipal())).with(csrf())
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        UUID conversationId = extractConvId(createResult.getResponse().getRedirectedUrl());

        MvcResult async = mockMvc.perform(get("/chat/conversations/" + conversationId + "/stream")
                        .with(user(asPrincipal()))
                        .param("question", "O que é rasterização?"))
                .andExpect(request().asyncStarted())
                .andReturn();
        async.getAsyncResult(3_000L);

        var assistant = waitForAssistant(conversationId, 3_000L);

        assertThat(assistant.getContent())
                .isEqualTo("Rasterização converte primitivas em pixels.");
        assertThat(assistant.getResponseTimeMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(assistant.getRole().name()).isEqualTo("ASSISTANT");

        var reloaded = messages.findByIdWithCitations(assistant.getId()).orElseThrow();
        assertThat(reloaded.getCitations()).hasSize(1);
        assertThat(reloaded.getCitations().get(0).getChunk().getId())
                .isEqualTo(chunk.getId());
    }

    @Test
    @DisplayName("/stream: erro do LLM durante streaming vira mensagem amigável")
    void streamingErrorFlow() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("Connection refused")));

        var createResult = mockMvc.perform(post("/chat/conversations")
                        .with(user(asPrincipal())).with(csrf())
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        UUID conversationId = extractConvId(createResult.getResponse().getRedirectedUrl());

        MvcResult async = mockMvc.perform(get("/chat/conversations/" + conversationId + "/stream")
                        .with(user(asPrincipal()))
                        .param("question", "qualquer coisa"))
                .andExpect(request().asyncStarted())
                .andReturn();
        async.getAsyncResult(3_000L);

        var assistant = waitForAssistant(conversationId, 3_000L);
        assertThat(assistant.getContent())
                .startsWith("⚠")
                .containsIgnoringCase("LM Studio");
    }

    @Test
    @DisplayName("export Markdown retorna conversa em formato MD com pergunta + resposta + fontes")
    void exportMarkdown() throws Exception {
        Conversation conv = conversations.save(Conversation.builder()
                .subject(subject).title("O que é rasterização?").build());
        messages.save(com.acervo.domain.Message.builder()
                .conversation(conv)
                .role(com.acervo.domain.Message.Role.USER)
                .content("O que é rasterização?")
                .build());
        messages.save(com.acervo.domain.Message.builder()
                .conversation(conv)
                .role(com.acervo.domain.Message.Role.ASSISTANT)
                .content("Rasterização converte primitivas em pixels.")
                .responseTimeMs(1234L)
                .build());

        var result = mockMvc.perform(get("/chat/conversations/" + conv.getId() + "/export")
                        .with(user(asPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("# O que é rasterização?");
        assertThat(body).contains("Pergunta");
        assertThat(body).contains("Resposta");
        assertThat(body).contains("Rasterização converte primitivas em pixels.");
        assertThat(body).contains("1.2s");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains(".md");
    }

    @Test
    @DisplayName("isolamento: usuário B não vê matérias do usuário A")
    void isolationBetweenUsers() throws Exception {
        User other = userService.signup("outro@acervo.dev", "senha123",
                "Outro", "Aluno", User.Role.ALUNO);
        AcervoUserDetails otherPrincipal = new AcervoUserDetails(other);

        // Tentando criar conversa na matéria do user A → service rejeita com
        // IllegalStateException, que MockMvc propaga como ServletException.
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                mockMvc.perform(post("/chat/conversations")
                        .with(user(otherPrincipal)).with(csrf())
                        .param("subjectId", subject.getId().toString())));

        // E o list de matérias de B deve estar vazio
        var listForOther = subjects.findByOwner_IdOrderByNameAsc(other.getId());
        assertThat(listForOther).isEmpty();
    }

    private ChatResponse chunkOf(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private com.acervo.domain.Message waitForAssistant(UUID conversationId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var msgs = messages.findByConversation_IdOrderByCreatedAtAsc(conversationId);
            if (msgs.size() >= 2) return msgs.get(msgs.size() - 1);
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError(
                "ASSISTANT message não apareceu em " + timeoutMs + "ms (conversa " + conversationId + ")");
    }

    private UUID extractConvId(String redirectUrl) {
        String marker = "conv=";
        int i = redirectUrl.indexOf(marker);
        return UUID.fromString(redirectUrl.substring(i + marker.length()));
    }
}
