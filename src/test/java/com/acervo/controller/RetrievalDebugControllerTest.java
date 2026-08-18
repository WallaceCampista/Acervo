package com.acervo.controller;

import com.acervo.AbstractIntegrationTest;
import com.acervo.config.AcervoUserDetails;
import com.acervo.domain.Chunk;
import com.acervo.domain.Document;
import com.acervo.domain.Subject;
import com.acervo.domain.User;
import com.acervo.repository.AuditLogRepository;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.DocumentRepository;
import com.acervo.repository.SubjectRepository;
import com.acervo.repository.UserRepository;
import com.acervo.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rotas de diagnóstico de retrieval: contrato, autorização e não-geração. */
@AutoConfigureMockMvc
class RetrievalDebugControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired SubjectRepository subjects;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;
    @Autowired AuditLogRepository auditLogs;

    @MockBean VectorStore vectorStore;
    @MockBean ChatModel chatModel;

    private User owner;
    private Subject subject;
    private Chunk chunk;

    @BeforeEach
    void seed() {
        cleanup();
        owner = userService.signup("dono@acervo.dev", "senha123",
                "Dono", "Teste", User.Role.ALUNO);
        subject = subjects.save(Subject.builder()
                .name("Biologia").color("#7fae8f").owner(owner).build());
        Document doc = documents.save(Document.builder()
                .subject(subject).originalName("celula.pdf")
                .storedPath("data/celula.pdf").extension("PDF")
                .sizeBytes(1024).status(Document.Status.INDEXED).build());
        chunk = chunks.save(Chunk.builder()
                .document(doc).ordinal(0)
                .content("A mitocôndria produz ATP por fosforilação oxidativa.")
                .pageLabel("p. 7").tokenCount(9).build());
    }

    @AfterEach
    void cleanup() {
        chunks.deleteAll();
        documents.deleteAll();
        subjects.deleteAll();
        auditLogs.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("preview devolve os trechos recuperados sem chamar o LLM")
    void previewReturnsChunksWithoutGenerating() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(retrievedDocument()));

        mockMvc.perform(get("/api/retrieval/preview")
                        .with(user(new AcervoUserDetails(owner)))
                        .param("subjectId", subject.getId().toString())
                        .param("q", "zzqa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returned").value(1))
                .andExpect(jsonPath("$.wouldAbstain").value(false))
                .andExpect(jsonPath("$.items[0].chunkId").value(chunk.getId().toString()))
                .andExpect(jsonPath("$.items[0].pageLabel").value("p. 7"));

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("eval devolve recall@k e MRR do gabarito")
    void evalReturnsMetrics() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(retrievedDocument()));

        mockMvc.perform(post("/api/retrieval/eval")
                        .with(user(new AcervoUserDetails(owner)))
                        .param("subjectId", subject.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            [{"question":"zzqa","expectedSnippets":["mitocondria produz ATP"]}]
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases").value(1))
                .andExpect(jsonPath("$.hits").value(1))
                .andExpect(jsonPath("$.recallAtK").value(1.0))
                .andExpect(jsonPath("$.mrr").value(1.0));

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("matéria de outro usuário devolve 404 e não vaza conteúdo")
    void otherUsersSubjectIsNotFound() throws Exception {
        User intruder = userService.signup("intruso@acervo.dev", "senha123",
                "Intruso", "Teste", User.Role.ALUNO);

        mockMvc.perform(get("/api/retrieval/preview")
                        .with(user(new AcervoUserDetails(intruder)))
                        .param("subjectId", subject.getId().toString())
                        .param("q", "zzqa"))
                .andExpect(status().isNotFound());

        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("sem autenticação não passa")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/retrieval/preview")
                        .param("subjectId", subject.getId().toString())
                        .param("q", "zzqa"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("gabarito vazio devolve 400")
    void emptyGoldenSetIsRejected() throws Exception {
        mockMvc.perform(post("/api/retrieval/eval")
                        .with(user(new AcervoUserDetails(owner)))
                        .param("subjectId", subject.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.ai.document.Document retrievedDocument() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunkId", chunk.getId().toString());
        meta.put("documentName", "celula.pdf");
        meta.put("pageLabel", "p. 7");
        meta.put("distance", 0.14);
        return new org.springframework.ai.document.Document(
                chunk.getId().toString(), chunk.getContent(), meta);
    }
}
