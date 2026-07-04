package com.acervo.service;

import com.acervo.ai.AiGenerationService;
import com.acervo.domain.Chunk;
import com.acervo.domain.QuizQuestion;
import com.acervo.domain.Subject;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.QuizQuestionRepository;
import com.acervo.repository.SubjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizService {

    private static final String SYSTEM_PROMPT = """
            Você é o Acervo, um assistente de estudo. Gere questões de múltipla
            escolha (4 alternativas, UMA correta) a partir do material fornecido.
            As questões devem:
            - Ser fiéis ao texto (não invente).
            - Ter alternativas plausíveis (sem absurdos óbvios).
            - Vir com uma explicação curta da resposta certa, citando o documento.

            Devolva no formato exato:
            Q: <pergunta>
            A) <alternativa A>
            B) <alternativa B>
            C) <alternativa C>
            D) <alternativa D>
            CORRETA: <letra>
            EXPLICACAO: <explicação curta>
            FONTE: <nome do documento>
            ---
            (repita pra cada questão, separadas por uma linha de "---")
            """;

    // Orçamento de caracteres do material enviado ao LLM — ver nota em
    // FlashcardService. Sem o corte, gerar muitas questões estourava a janela
    // de contexto do modelo local ("n_keep >= n_ctx"). ~16k chars cabe no
    // contexto de 8192 tokens do Gemma 2 com folga pra geração.
    private static final int MAX_BODY_CHARS = 16_000;

    private final QuizQuestionRepository quizzes;
    private final SubjectRepository subjects;
    private final ChunkRepository chunks;
    private final AiGenerationService ai;

    public List<QuizQuestion> list(UUID subjectId) {
        return quizzes.findBySubject_IdOrderByCreatedAtDesc(subjectId);
    }

    public long count(UUID subjectId) {
        return quizzes.countBySubject_Id(subjectId);
    }

    public QuizQuestion get(UUID id) {
        return quizzes.findById(id).orElseThrow();
    }

    @Transactional
    public List<QuizQuestion> generate(UUID subjectId, int count,
                                       QuizQuestion.Difficulty difficulty) {
        Subject subject = subjects.findById(subjectId).orElseThrow();
        int sample = Math.min(60, Math.max(12, count * 4));
        List<Chunk> source = sampleChunks(subjectId, sample);
        if (source.isEmpty()) {
            throw new IllegalStateException(
                    "A matéria ainda não tem documentos indexados.");
        }
        String body = buildBody(source);
        String difficultyLabel = switch (difficulty) {
            case EASY -> "fácil — recall direto de definições";
            case MEDIUM -> "médio — aplicação ou interpretação";
            case HARD -> "difícil — análise comparativa ou síntese";
        };
        String userPrompt = """
                Matéria: %s
                Quantidade desejada: %d questões.
                Dificuldade: %s.

                Material:
                %s
                """.formatted(subject.getName(), count, difficultyLabel, body);

        String raw = ai.generate(SYSTEM_PROMPT, userPrompt);
        List<QuizQuestion> parsed = parse(raw, subject, difficulty);
        if (parsed.size() > count) parsed = parsed.subList(0, count);
        return quizzes.saveAll(parsed);
    }

    private List<Chunk> sampleChunks(UUID subjectId, int max) {
        List<Chunk> all = new ArrayList<>(
                chunks.findAll().stream()
                        .filter(c -> c.getDocument().getSubject().getId().equals(subjectId))
                        .toList());
        Collections.shuffle(all);
        return all.size() > max ? all.subList(0, max) : all;
    }

    private String buildBody(List<Chunk> source) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < source.size(); i++) {
            if (sb.length() >= MAX_BODY_CHARS) break;
            Chunk c = source.get(i);
            sb.append("--- Trecho ").append(i + 1).append(" — ")
              .append(c.getDocument().getOriginalName());
            if (c.getPageLabel() != null && !c.getPageLabel().isBlank()) {
                sb.append(" · ").append(c.getPageLabel());
            }
            sb.append(" ---\n");
            String content = c.getContent();
            int remaining = MAX_BODY_CHARS - sb.length();
            if (remaining <= 0) break;
            if (content.length() > remaining) content = content.substring(0, remaining);
            sb.append(content).append("\n\n");
        }
        return sb.toString();
    }

    private List<QuizQuestion> parse(String raw, Subject subject,
                                     QuizQuestion.Difficulty difficulty) {
        List<QuizQuestion> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String block : raw.split("(?m)^---\\s*$")) {
            String question = null;
            String[] options = new String[4];
            Integer correctIndex = null;
            String explanation = null, source = null;

            for (String line : block.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("Q:")) {
                    question = trimmed.substring(2).trim();
                } else if (trimmed.length() >= 2 && trimmed.charAt(1) == ')') {
                    char letter = trimmed.charAt(0);
                    int idx = "ABCD".indexOf(letter);
                    if (idx >= 0) options[idx] = trimmed.substring(2).trim();
                } else if (trimmed.startsWith("CORRETA:")) {
                    String letter = trimmed.substring(8).trim();
                    if (!letter.isEmpty()) {
                        int idx = "ABCD".indexOf(letter.charAt(0));
                        if (idx >= 0) correctIndex = idx;
                    }
                } else if (trimmed.startsWith("EXPLICACAO:")) {
                    explanation = trimmed.substring(11).trim();
                } else if (trimmed.startsWith("EXPLICAÇÃO:")) {
                    explanation = trimmed.substring("EXPLICAÇÃO:".length()).trim();
                } else if (trimmed.startsWith("FONTE:")) {
                    source = trimmed.substring(6).trim();
                }
            }

            if (question != null && correctIndex != null
                    && options[0] != null && options[1] != null
                    && options[2] != null && options[3] != null) {
                String optionsText = String.join("\n", options);
                out.add(QuizQuestion.builder()
                        .subject(subject)
                        .question(question)
                        .optionsText(optionsText)
                        .correctIndex(correctIndex)
                        .explanation(explanation)
                        .sourceDoc(source)
                        .difficulty(difficulty)
                        .build());
            }
        }
        return out;
    }
}
