package com.acervo.repository;

import com.acervo.AbstractIntegrationTest;
import com.acervo.domain.Chunk;
import com.acervo.domain.Document;
import com.acervo.domain.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ChunkRepositoryTest extends AbstractIntegrationTest {

    @Autowired SubjectRepository subjects;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;

    private Subject subject;
    private Document doc;

    @BeforeEach
    void seed() {
        chunks.deleteAll();
        documents.deleteAll();
        subjects.deleteAll();
        subject = subjects.save(Subject.builder().name("Química").color("#7fae8f").build());
        doc = documents.save(Document.builder()
                .subject(subject).originalName("aula.pdf")
                .storedPath("data/aula.pdf").extension("PDF")
                .sizeBytes(2048).status(Document.Status.INDEXED).build());
    }

    @Test
    @DisplayName("findByDocument_Id retorna chunks do documento")
    void findByDocument() {
        chunks.save(makeChunk(0, "intro"));
        chunks.save(makeChunk(1, "desenvolvimento"));
        chunks.save(makeChunk(2, "conclusão"));

        assertThat(chunks.findByDocument_Id(doc.getId())).hasSize(3)
                .extracting(Chunk::getContent)
                .containsExactlyInAnyOrder("intro", "desenvolvimento", "conclusão");
    }

    @Test
    @DisplayName("countByDocument_Subject_Id soma chunks da matéria")
    void countBySubject() {
        chunks.save(makeChunk(0, "c1"));
        chunks.save(makeChunk(1, "c2"));

        Document doc2 = documents.save(Document.builder()
                .subject(subject).originalName("outro.pdf")
                .storedPath("data/outro.pdf").extension("PDF")
                .sizeBytes(1024).status(Document.Status.INDEXED).build());
        chunks.save(Chunk.builder().document(doc2).ordinal(0)
                .content("c3").tokenCount(2).build());

        assertThat(chunks.countByDocument_Subject_Id(subject.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("deleteByDocument_Id apaga só do documento indicado")
    void deleteByDocument() {
        chunks.save(makeChunk(0, "a"));
        chunks.save(makeChunk(1, "b"));

        Document outro = documents.save(Document.builder()
                .subject(subject).originalName("outro.pdf")
                .storedPath("data/outro.pdf").extension("PDF")
                .sizeBytes(1024).status(Document.Status.INDEXED).build());
        chunks.save(Chunk.builder().document(outro).ordinal(0)
                .content("preservar").tokenCount(1).build());

        chunks.deleteByDocument_Id(doc.getId());
        chunks.flush();

        assertThat(chunks.findByDocument_Id(doc.getId())).isEmpty();
        assertThat(chunks.findByDocument_Id(outro.getId())).hasSize(1);
    }

    @Test
    @DisplayName("excluir document faz cascade nos chunks")
    void cascadeOnDocument() {
        chunks.save(makeChunk(0, "x"));
        chunks.save(makeChunk(1, "y"));
        UUID docId = doc.getId();

        documents.delete(doc);
        documents.flush();

        assertThat(chunks.findByDocument_Id(docId)).isEmpty();
    }

    private Chunk makeChunk(int ord, String content) {
        return Chunk.builder()
                .document(doc).ordinal(ord)
                .content(content).pageLabel("p. " + (ord + 1))
                .tokenCount(content.length()).build();
    }
}
