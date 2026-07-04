package com.acervo.repository;

import com.acervo.AbstractIntegrationTest;
import com.acervo.domain.Document;
import com.acervo.domain.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class DocumentRepositoryTest extends AbstractIntegrationTest {

    @Autowired SubjectRepository subjects;
    @Autowired DocumentRepository documents;

    private Subject subject;

    @BeforeEach
    void seed() {
        documents.deleteAll();
        subjects.deleteAll();
        subject = subjects.save(Subject.builder().name("Física").color("#9a8fc4").build());
    }

    @Test
    @DisplayName("findBySubject_IdOrderByUploadedAtDesc retorna do mais recente pro mais antigo")
    void findBySubjectOrdered() {
        Document a = save("a.pdf", Document.Status.INDEXED);
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        Document b = save("b.pdf", Document.Status.PROCESSING);
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        Document c = save("c.pdf", Document.Status.FAILED);

        List<Document> ordered = documents.findBySubject_IdOrderByUploadedAtDesc(subject.getId());
        assertThat(ordered).extracting(Document::getOriginalName)
                .containsExactly(c.getOriginalName(), b.getOriginalName(), a.getOriginalName());
    }

    @Test
    @DisplayName("count por subject")
    void countBySubject() {
        save("a.pdf", Document.Status.INDEXED);
        save("b.pdf", Document.Status.INDEXED);
        save("c.pdf", Document.Status.PROCESSING);

        assertThat(documents.countBySubject_Id(subject.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("count por subject + status filtra corretamente")
    void countBySubjectAndStatus() {
        save("a.pdf", Document.Status.INDEXED);
        save("b.pdf", Document.Status.INDEXED);
        save("c.pdf", Document.Status.PROCESSING);
        save("d.pdf", Document.Status.FAILED);
        save("e.pdf", Document.Status.CANCELLED);

        assertThat(documents.countBySubject_IdAndStatus(subject.getId(), Document.Status.INDEXED))
                .isEqualTo(2);
        assertThat(documents.countBySubject_IdAndStatus(subject.getId(), Document.Status.PROCESSING))
                .isEqualTo(1);
        assertThat(documents.countBySubject_IdAndStatus(subject.getId(), Document.Status.CANCELLED))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("excluir subject faz cascade nos documents")
    void cascadeDelete() {
        save("a.pdf", Document.Status.INDEXED);
        save("b.pdf", Document.Status.INDEXED);
        UUID subjectId = subject.getId();

        subjects.delete(subject);
        subjects.flush();

        assertThat(documents.countBySubject_Id(subjectId)).isZero();
    }

    private Document save(String name, Document.Status status) {
        return documents.save(Document.builder()
                .subject(subject)
                .originalName(name)
                .storedPath("data/uploads/" + UUID.randomUUID() + ".pdf")
                .extension("PDF")
                .sizeBytes(1024)
                .status(status)
                .build());
    }
}
