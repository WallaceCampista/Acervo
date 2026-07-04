package com.acervo.repository;

import com.acervo.AbstractIntegrationTest;
import com.acervo.domain.Citation;
import com.acervo.domain.Chunk;
import com.acervo.domain.Conversation;
import com.acervo.domain.Document;
import com.acervo.domain.Message;
import com.acervo.domain.Subject;
import com.acervo.domain.User;
import com.acervo.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class MessageAndProfileRepositoryTest extends AbstractIntegrationTest {

    @Autowired SubjectRepository subjects;
    @Autowired ConversationRepository conversations;
    @Autowired DocumentRepository documents;
    @Autowired ChunkRepository chunks;
    @Autowired MessageRepository messages;
    @Autowired UserRepository users;
    @Autowired UserService userService;

    private User newOwner(String email) {
        return userService.signup(email, "senha123",
                "Wallace", "Rocha", User.Role.ALUNO);
    }

    @Test
    @DisplayName("Message persiste responseTimeMs e citations (cascade)")
    void messageWithCitations() {
        User owner = newOwner("dir@acervo.dev");
        Subject s = subjects.save(Subject.builder()
                .name("Direito").color("#9a8fc4").owner(owner).build());
        Document d = documents.save(Document.builder()
                .subject(s).originalName("constituicao.pdf")
                .storedPath("data/c.pdf").extension("PDF")
                .sizeBytes(2048).status(Document.Status.INDEXED).build());
        Chunk chunk = chunks.save(Chunk.builder()
                .document(d).ordinal(0)
                .content("Art. 1º").pageLabel("p. 1")
                .tokenCount(2).build());
        Conversation c = conversations.save(Conversation.builder()
                .subject(s).title("Constituição").build());

        Message m = Message.builder()
                .conversation(c).role(Message.Role.ASSISTANT)
                .content("Resposta").responseTimeMs(1234L).build();
        m.getCitations().add(Citation.builder()
                .message(m).chunk(chunk)
                .relevance(0.91).excerpt("Art. 1º").build());

        Message saved = messages.save(m);

        Message reloaded = messages.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getResponseTimeMs()).isEqualTo(1234L);
        assertThat(reloaded.getCitations()).hasSize(1);
        assertThat(reloaded.getCitations().get(0).getRelevance()).isEqualTo(0.91);
    }

    @Test
    @DisplayName("excluir conversation faz cascade nas messages")
    void cascadeMessage() {
        User owner = newOwner("filo@acervo.dev");
        Subject s = subjects.save(Subject.builder()
                .name("Filosofia").color("#7fae8f").owner(owner).build());
        Conversation c = conversations.save(Conversation.builder()
                .subject(s).title("Sócrates").build());
        messages.save(Message.builder()
                .conversation(c).role(Message.Role.USER)
                .content("O que sei?").build());
        UUID cid = c.getId();

        conversations.delete(c);
        conversations.flush();

        // Toda mensagem daquela conversa deve ter sumido
        assertThat(messages.findAll().stream()
                .anyMatch(msg -> msg.getConversation().getId().equals(cid)))
                .isFalse();
    }

    @Test
    @DisplayName("User signup persiste hash bcrypt e fullName/initials funcionam")
    void userSignup() {
        User u = userService.signup("walt@acervo.dev", "senha123",
                "Walt", "Whitman", User.Role.PROFESSOR);
        User r = users.findById(u.getId()).orElseThrow();
        assertThat(r.getEmail()).isEqualTo("walt@acervo.dev");
        assertThat(r.getPasswordHash()).startsWith("$2"); // bcrypt
        assertThat(r.fullName()).isEqualTo("Walt Whitman");
        assertThat(r.initials()).isEqualTo("WW");
        assertThat(r.getRole()).isEqualTo(User.Role.PROFESSOR);
    }
}
