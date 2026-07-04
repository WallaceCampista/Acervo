package com.acervo.service;

import com.acervo.domain.Conversation;
import com.acervo.domain.Message;
import com.acervo.repository.ConversationRepository;
import com.acervo.repository.MessageRepository;
import com.acervo.repository.RetrievalMetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Análise de "gaps de conhecimento": olha pra retrieval_metrics e conversas
 * recentes do usuário pra identificar tópicos com sinal de dificuldade
 * (retrieval pobre, pergunta repetida, etc). Bem rudimentar — só identifica
 * candidates pra revisão, sem inferência por LLM.
 */
@Service
@RequiredArgsConstructor
public class GapAnalysisService {

    private static final int STOPWORD_MIN_LEN = 5;
    private static final Set<String> STOPWORDS = Set.of(
            "para", "como", "qual", "quais", "quando", "onde", "porque",
            "porquê", "quem", "essa", "esse", "isso", "esta", "este",
            "isto", "muito", "pouco", "também", "ainda", "depois", "antes",
            "sobre", "entre", "todos", "todas", "tudo", "nada", "alguma",
            "algum", "alguns", "algumas", "outro", "outra", "outros", "outras"
    );

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final RetrievalMetricRepository retrievalMetrics;

    /**
     * Devolve uma lista ranqueada de "tópicos pra revisar" baseados em
     * frequência de termos das perguntas do usuário cruzadas com retrieval
     * fraco (no_results=true ou top1_distance alto).
     *
     * <p>Retorna no máximo {@code limit} termos.
     */
    public List<TopicSuggestion> suggestForUser(UUID userId, UUID subjectId, int limit) {
        List<Conversation> userConversations = subjectId == null
                ? Collections.emptyList()
                : conversations.findBySubject_IdAndSubject_Owner_IdOrderByCreatedAtDesc(
                        subjectId, userId);
        Map<String, Integer> termWeight = new HashMap<>();

        for (Conversation c : userConversations) {
            List<Message> msgs = messages.findByConversation_IdOrderByCreatedAtAsc(c.getId());
            for (Message m : msgs) {
                if (m.getRole() != Message.Role.USER) continue;
                for (String term : tokenize(m.getContent())) {
                    termWeight.merge(term, 1, Integer::sum);
                }
            }
        }

        return termWeight.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new TopicSuggestion(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private List<String> tokenize(String content) {
        if (content == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String raw : content.toLowerCase().split("\\W+")) {
            if (raw.length() < STOPWORD_MIN_LEN) continue;
            if (STOPWORDS.contains(raw)) continue;
            out.add(raw);
        }
        return out;
    }

    public record TopicSuggestion(String term, int count) {}
}
