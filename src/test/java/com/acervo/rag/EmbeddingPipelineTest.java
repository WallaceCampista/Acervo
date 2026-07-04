package com.acervo.rag;

import com.acervo.AbstractIntegrationTest;
import com.acervo.domain.Document;
import com.acervo.domain.Subject;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.DocumentRepository;
import com.acervo.repository.SubjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Testes do pipeline de indexação. Documento real (TXT) no disco; extração
 * + chunking acontecem de verdade; VectorStore é mockado pra não depender
 * do servidor de embedding.
 */
class EmbeddingPipelineTest extends AbstractIntegrationTest {

    @Autowired EmbeddingPipeline pipeline;
    @Autowired SubjectRepository subjects;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;

    @MockBean VectorStore vectorStore;

    private Subject subject;
    private Path tempFile;

    @BeforeEach
    void seed() throws IOException {
        cleanup();
        subject = subjects.save(Subject.builder()
                .name("Engenharia").color("#7fae8f").build());
        // Texto suficientemente grande pra gerar pelo menos um chunk
        String content = "Engenharia de software é a aplicação de uma "
                + "abordagem sistemática, disciplinada e quantificável "
                + "ao desenvolvimento, operação e manutenção de software. "
                + "Inclui análise de requisitos, design, programação, "
                + "testes e manutenção. O ciclo de vida pode seguir "
                + "modelos como cascata, espiral ou ágil.";
        tempFile = Files.createTempFile("acervo-test-", ".txt");
        Files.writeString(tempFile, content, StandardCharsets.UTF_8);
    }

    @AfterEach
    void cleanup() throws IOException {
        chunks.deleteAll();
        documents.deleteAll();
        subjects.deleteAll();
        if (tempFile != null) {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    @Test
    @DisplayName("caminho feliz: PROCESSING → INDEXED, chunks salvos, vectorStore.add chamado, processedAt setado")
    void happyPath() {
        Document doc = persistProcessingDocument();

        pipeline.process(doc.getId());

        Document reloaded = documents.findById(doc.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Document.Status.INDEXED);
        assertThat(reloaded.getProcessedAt()).isNotNull();
        assertThat(reloaded.getFailureReason()).isNull();
        assertThat(reloaded.getPages()).isNotNull().isGreaterThan(0);

        // Chunks foram criados pra esse documento
        List<com.acervo.domain.Chunk> created = chunks.findByDocument_Id(doc.getId());
        assertThat(created).isNotEmpty();
        assertThat(created.get(0).getContent()).contains("Engenharia");

        // vectorStore.add chamado pelo menos uma vez com a lista de docs
        verify(vectorStore).add(anyList());
    }

    @Test
    @DisplayName("falha no embedding: PROCESSING → FAILED com failureReason traduzida")
    void failurePath() {
        doThrow(new RuntimeException("Connection refused"))
                .when(vectorStore).add(any());

        Document doc = persistProcessingDocument();

        pipeline.process(doc.getId());

        Document reloaded = documents.findById(doc.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Document.Status.FAILED);
        assertThat(reloaded.getProcessedAt()).isNotNull();
        // Mensagem traduzida pelo AiFailureTranslator quando há "Connection refused"
        assertThat(reloaded.getFailureReason())
                .isNotNull()
                .containsIgnoringCase("LM Studio");
    }

    @Test
    @DisplayName("documento já CANCELLED não é sobrescrito mesmo se o pipeline rodar")
    void cancelledIsNotOverwritten() {
        Document doc = persistProcessingDocument();
        // Marca como CANCELLED antes do pipeline checar
        doc.setStatus(Document.Status.CANCELLED);
        documents.save(doc);

        pipeline.process(doc.getId());

        Document reloaded = documents.findById(doc.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Document.Status.CANCELLED);
    }

    @Test
    @DisplayName("documento inexistente é ignorado silenciosamente (não explode)")
    void missingDocumentIsTolerated() {
        // ID que não existe — não deve lançar
        pipeline.process(UUID.randomUUID());
        // Sem assertion: o teste passa se não houver exception
    }

    private Document persistProcessingDocument() {
        long size;
        try {
            size = Files.size(tempFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return documents.save(Document.builder()
                .subject(subject)
                .originalName("eng-soft.txt")
                .storedPath(tempFile.toString())
                .extension("TXT")
                .sizeBytes(size)
                .status(Document.Status.PROCESSING)
                .build());
    }
}
