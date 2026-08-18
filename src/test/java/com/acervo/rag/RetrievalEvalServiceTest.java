package com.acervo.rag;

import com.acervo.AbstractIntegrationTest;
import com.acervo.domain.Chunk;
import com.acervo.domain.Document;
import com.acervo.domain.Subject;
import com.acervo.rag.RetrievalEvalService.EvalReport;
import com.acervo.rag.RetrievalEvalService.GoldenCase;
import com.acervo.rag.RetrievalEvalService.RetrievalPreview;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.DocumentRepository;
import com.acervo.repository.SubjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Avaliação de retrieval sem geração.
 *
 * <p>As perguntas são propositalmente sem sentido ({@code zzqa}, …) para que a
 * perna lexical não case com nada: assim o pool vem só do VectorStore mockado
 * e os ranks são determinísticos. O que está sob teste aqui é a aritmética do
 * gabarito, não a qualidade do embedding.
 */
class RetrievalEvalServiceTest extends AbstractIntegrationTest {

    @Autowired RetrievalEvalService evalService;
    @Autowired SubjectRepository subjects;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;

    @MockBean VectorStore vectorStore;
    @MockBean ChatModel chatModel;

    private Subject subject;
    private Chunk uniao;
    private Chunk intersecao;

    @BeforeEach
    void seed() {
        cleanup();
        subject = subjects.save(Subject.builder()
                .name("Matemática Discreta").color("#9a8fc4").build());
        Document doc = documents.save(Document.builder()
                .subject(subject).originalName("conjuntos.pdf")
                .storedPath("data/conjuntos.pdf").extension("PDF")
                .sizeBytes(1024).status(Document.Status.INDEXED).build());
        uniao = chunks.save(Chunk.builder()
                .document(doc).ordinal(0)
                .content("União: A ∪ B = {x : x ∈ A ∨ x ∈ B}.")
                .pageLabel("p. 3").tokenCount(8).build());
        intersecao = chunks.save(Chunk.builder()
                .document(doc).ordinal(1)
                .content("Interseção: A ∩ B = {x : x ∈ A ∧ x ∈ B}.")
                .pageLabel("p. 4").tokenCount(8).build());
    }

    @AfterEach
    void cleanup() {
        chunks.deleteAll();
        documents.deleteAll();
        subjects.deleteAll();
    }

    @Test
    @DisplayName("preview mostra o que o LLM receberia — sem chamar o LLM")
    void previewDoesNotGenerate() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(asDocument(uniao, 0.12)));

        RetrievalPreview preview = evalService.preview(subject.getId(), "zzqa");

        verify(chatModel, never()).call(any(Prompt.class));
        assertThat(preview.returned()).isEqualTo(1);
        assertThat(preview.wouldAbstain()).isFalse();
        assertThat(preview.vectorHits()).isEqualTo(1);
        assertThat(preview.lexicalHits()).isZero();
        assertThat(preview.approxContextTokens()).isPositive();

        var item = preview.items().get(0);
        assertThat(item.rank()).isEqualTo(1);
        assertThat(item.chunkId()).isEqualTo(uniao.getId());
        assertThat(item.documentName()).isEqualTo("conjuntos.pdf");
        assertThat(item.pageLabel()).isEqualTo("p. 3");
        assertThat(item.source()).isEqualTo(RagService.Source.VECTOR);
        assertThat(item.distance()).isEqualTo(0.12);
        assertThat(item.similarity()).isCloseTo(0.88, within(1e-9));
    }

    @Test
    @DisplayName("preview sinaliza wouldAbstain quando nada passa no filtro")
    void previewFlagsAbstention() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        RetrievalPreview preview = evalService.preview(subject.getId(), "zzqa");

        assertThat(preview.returned()).isZero();
        assertThat(preview.wouldAbstain()).isTrue();
        assertThat(preview.items()).isEmpty();
    }

    @Test
    @DisplayName("recall@k, MRR e taxa de abstenção sobre um gabarito de 3 casos")
    void computesRecallAndMrr() {
        // zzqa → [interseção, união]: o esperado vem em 2º  → RR = 1/2
        // zzqb → [união]:             o esperado vem em 1º  → RR = 1
        // zzqc → []:                  abstenção, sem hit    → RR = 0
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            String q = inv.getArgument(0, SearchRequest.class).getQuery();
            return switch (q) {
                case "zzqa" -> List.of(asDocument(intersecao, 0.30), asDocument(uniao, 0.35));
                case "zzqb" -> List.of(asDocument(uniao, 0.10));
                default -> List.of();
            };
        });

        EvalReport report = evalService.evaluate(subject.getId(), List.of(
                new GoldenCase("zzqa", null, List.of("A ∪ B")),
                new GoldenCase("zzqb", List.of(uniao.getId()), null),
                new GoldenCase("zzqc", null, List.of("qualquer coisa"))));

        verify(chatModel, never()).call(any(Prompt.class));
        assertThat(report.cases()).isEqualTo(3);
        assertThat(report.hits()).isEqualTo(2);
        assertThat(report.recallAtK()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(report.mrr()).isCloseTo(0.5, within(1e-9));
        assertThat(report.abstentions()).isEqualTo(1);
        assertThat(report.abstentionRate()).isCloseTo(1.0 / 3, within(1e-9));

        assertThat(report.results()).extracting(RetrievalEvalService.CaseResult::hitRank)
                .containsExactly(2, 1, 0);
        assertThat(report.results()).extracting(RetrievalEvalService.CaseResult::abstained)
                .containsExactly(false, false, true);
    }

    @Test
    @DisplayName("snippet do gabarito casa sem acento e com espaçamento diferente")
    void snippetMatchingIsLenient() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(asDocument(uniao, 0.12)));

        EvalReport report = evalService.evaluate(subject.getId(), List.of(
                new GoldenCase("zzqa", null, List.of("uniao:   A"))));

        assertThat(report.hits()).isEqualTo(1);
        assertThat(report.results().get(0).hitRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("caso que explode vira erro no resultado sem derrubar a rodada")
    void caseFailureIsIsolated() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            String q = inv.getArgument(0, SearchRequest.class).getQuery();
            if ("zzqa".equals(q)) throw new IllegalStateException("índice fora do ar");
            return List.of(asDocument(uniao, 0.12));
        });

        EvalReport report = evalService.evaluate(subject.getId(), List.of(
                new GoldenCase("zzqa", null, List.of("A ∪ B")),
                new GoldenCase("zzqb", null, List.of("A ∪ B"))));

        assertThat(report.cases()).isEqualTo(2);
        assertThat(report.hits()).isEqualTo(1);
        assertThat(report.results().get(0).error()).contains("índice fora do ar");
        assertThat(report.results().get(1).error()).isNull();
    }

    private org.springframework.ai.document.Document asDocument(Chunk chunk, double distance) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunkId", chunk.getId().toString());
        meta.put("documentName", "conjuntos.pdf");
        meta.put("pageLabel", chunk.getPageLabel());
        meta.put("distance", distance);
        return new org.springframework.ai.document.Document(
                chunk.getId().toString(), chunk.getContent(), meta);
    }
}
