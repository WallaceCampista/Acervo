package com.acervo.service;

import com.acervo.domain.Citation;
import com.acervo.domain.Conversation;
import com.acervo.domain.Message;
import com.acervo.domain.Subject;
import com.acervo.repository.ConversationRepository;
import com.acervo.repository.MessageRepository;
import com.acervo.repository.SubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final DateTimeFormatter EXPORT_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", new Locale("pt", "BR"));

    private final ConversationRepository conversations;
    private final SubjectRepository subjects;
    private final MessageRepository messages;

    public List<Conversation> listBySubject(UUID subjectId) {
        return conversations.findBySubject_IdOrderByCreatedAtDesc(subjectId);
    }

    public List<Conversation> listBySubjectForOwner(UUID subjectId, UUID ownerId) {
        return conversations.findBySubject_IdAndSubject_Owner_IdOrderByCreatedAtDesc(
                subjectId, ownerId);
    }

    public Conversation get(UUID id) {
        return conversations.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Conversation getForOwner(UUID id, UUID ownerId) {
        Conversation c = conversations.findById(id).orElseThrow();
        ensureOwner(c, ownerId);
        return c;
    }

    /**
     * Busca uma mensagem garantindo que ela pertence a uma conversa do
     * usuário corrente. Usado para servir a imagem anexada com segurança.
     */
    @Transactional(readOnly = true)
    public Message getMessageForOwner(UUID messageId, UUID ownerId) {
        Message m = messages.findById(messageId).orElseThrow();
        ensureOwner(m.getConversation(), ownerId);
        return m;
    }

    @Transactional
    public Conversation create(UUID subjectId, UUID ownerId) {
        Subject subject = subjects.findByIdAndOwner_Id(subjectId, ownerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Matéria não acessível: " + subjectId));
        Conversation c = Conversation.builder().subject(subject).build();
        return conversations.save(c);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        Conversation c = conversations.findById(id).orElseThrow();
        ensureOwner(c, ownerId);
        conversations.delete(c);
    }

    private static void ensureOwner(Conversation c, UUID ownerId) {
        Subject s = c.getSubject();
        if (s == null || s.getOwner() == null
                || !s.getOwner().getId().equals(ownerId)) {
            throw new IllegalStateException(
                    "Conversa não pertence ao usuário corrente");
        }
    }

    /**
     * Renderiza a conversa como Markdown autocontido. Inclui pergunta,
     * resposta, fontes com nome do documento + página + relevância.
     */
    @Transactional(readOnly = true)
    public String exportMarkdown(UUID conversationId) {
        Conversation c = conversations.findById(conversationId).orElseThrow();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(c.getTitle()).append("\n\n");
        sb.append("> **Matéria:** ").append(c.getSubject().getName()).append("  \n");
        sb.append("> **Início:** ").append(EXPORT_DATE.format(c.getCreatedAt())).append("\n\n");
        sb.append("---\n\n");

        for (Message m : c.getMessages()) {
            if (m.getRole() == Message.Role.USER) {
                sb.append("## 🧑 Pergunta\n\n").append(m.getContent().trim()).append("\n\n");
            } else {
                sb.append("### 🤖 Resposta\n\n").append(m.getContent().trim()).append("\n\n");
                if (m.getResponseTimeMs() != null) {
                    long ms = m.getResponseTimeMs();
                    String t = ms >= 1000 ? String.format(Locale.ROOT, "%.1fs", ms / 1000.0) : ms + "ms";
                    sb.append("_Respondido em ").append(t).append("._\n\n");
                }
                if (!m.getCitations().isEmpty()) {
                    sb.append("**Fontes consultadas:**\n\n");
                    for (Citation ct : m.getCitations()) {
                        String docName = ct.getChunk().getDocument().getOriginalName();
                        String page = ct.getChunk().getPageLabel();
                        long pct = Math.round(ct.getRelevance() * 100);
                        sb.append("- **").append(docName).append("**");
                        if (page != null && !page.isBlank()) sb.append(" · ").append(page);
                        sb.append(" — relevância ").append(pct).append("%\n");
                        if (ct.getExcerpt() != null && !ct.getExcerpt().isBlank()) {
                            sb.append("  > ").append(ct.getExcerpt().replace("\n", " ")).append("\n");
                        }
                    }
                    sb.append("\n");
                }
                sb.append("---\n\n");
            }
        }
        return sb.toString();
    }
}
