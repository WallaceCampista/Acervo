package com.acervo.repository;

import com.acervo.AbstractIntegrationTest;
import com.acervo.domain.Conversation;
import com.acervo.domain.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ConversationRepositoryTest extends AbstractIntegrationTest {

    @Autowired SubjectRepository subjects;
    @Autowired ConversationRepository conversations;

    private Subject subject;

    @BeforeEach
    void seed() {
        conversations.deleteAll();
        subjects.deleteAll();
        subject = subjects.save(Subject.builder().name("Português").color("#c98b6b").build());
    }

    @Test
    @DisplayName("findBySubject_IdOrderByCreatedAtDesc retorna do mais recente pro mais antigo")
    void findOrdered() {
        Conversation antiga = conversations.save(Conversation.builder()
                .subject(subject).title("Antiga")
                .createdAt(OffsetDateTime.now().minusDays(2)).build());
        Conversation recente = conversations.save(Conversation.builder()
                .subject(subject).title("Recente")
                .createdAt(OffsetDateTime.now()).build());

        List<Conversation> list = conversations
                .findBySubject_IdOrderByCreatedAtDesc(subject.getId());

        assertThat(list).extracting(Conversation::getTitle)
                .containsExactly(recente.getTitle(), antiga.getTitle());
    }

    @Test
    @DisplayName("excluir subject faz cascade nas conversations")
    void cascadeFromSubject() {
        conversations.save(Conversation.builder()
                .subject(subject).title("X").build());
        UUID subjectId = subject.getId();

        subjects.delete(subject);
        subjects.flush();

        assertThat(conversations.findBySubject_IdOrderByCreatedAtDesc(subjectId)).isEmpty();
    }
}
