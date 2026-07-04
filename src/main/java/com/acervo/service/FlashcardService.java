package com.acervo.service;

import com.acervo.ai.AiGenerationService;
import com.acervo.domain.Chunk;
import com.acervo.domain.Flashcard;
import com.acervo.domain.Subject;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.FlashcardRepository;
import com.acervo.repository.SubjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlashcardService {

    private static final String SYSTEM_PROMPT = """
            Você é o Acervo, um assistente de estudo. Gere flashcards do tipo
            pergunta-resposta a partir do material fornecido. Cada flashcard deve:
            - Ser autocontido (a pergunta deve fazer sentido sem o material).
            - Ter resposta concisa e correta, fiel ao texto.
            - Cobrir um conceito por vez.

            Devolva no formato exato:
            P: <pergunta>
            R: <resposta>
            F: <nome do documento ou seção>
            ---
            (repita pra cada flashcard, separados por uma linha de "---")

            Não invente informações que não estejam no material.
            """;

    // Orçamento de caracteres do material enviado ao LLM. O modelo local roda
    // com janela de contexto limitada (Gemma 2 = 8192 tokens) e mandar dezenas
    // de chunks inteiros estourava o contexto (erro "n_keep >= n_ctx"). ~16k
    // chars ≈ 4–5k tokens de input, deixando folga pro system prompt e,
    // principalmente, pra própria geração das respostas (que também consome
    // contexto). O shuffle em sampleChunks garante variedade entre gerações
    // mesmo com o corte.
    private static final int MAX_BODY_CHARS = 16_000;

    private final FlashcardRepository flashcards;
    private final SubjectRepository subjects;
    private final ChunkRepository chunks;
    private final AiGenerationService ai;

    public List<Flashcard> list(UUID subjectId) {
        return flashcards.findBySubject_IdOrderByCreatedAtDesc(subjectId);
    }

    public List<Flashcard> dueNow(UUID subjectId) {
        return flashcards.findBySubject_IdAndDueAtLessThanEqualOrderByDueAtAsc(
                subjectId, OffsetDateTime.now());
    }

    public long countDue(UUID subjectId) {
        return flashcards.countBySubject_IdAndDueAtLessThanEqual(
                subjectId, OffsetDateTime.now());
    }

    public long total(UUID subjectId) {
        return flashcards.countBySubject_Id(subjectId);
    }

    /**
     * Gera {@code count} flashcards via LLM a partir dos chunks da matéria.
     * Persiste todos e devolve os criados. Se o LLM gerar menos, devolve o
     * que veio. Se mais, trunca em {@code count}.
     */
    @Transactional
    public List<Flashcard> generate(UUID subjectId, int count) {
        Subject subject = subjects.findById(subjectId).orElseThrow();
        int sample = Math.min(60, Math.max(12, count * 3));
        List<Chunk> source = sampleChunks(subjectId, sample);
        if (source.isEmpty()) {
            throw new IllegalStateException(
                    "A matéria ainda não tem documentos indexados.");
        }
        String body = buildBody(source);
        String userPrompt = """
                Matéria: %s
                Quantidade desejada: %d flashcards.

                Material:
                %s
                """.formatted(subject.getName(), count, body);

        String raw = ai.generate(SYSTEM_PROMPT, userPrompt);
        List<Flashcard> parsed = parse(raw, subject);
        if (parsed.size() > count) parsed = parsed.subList(0, count);
        return flashcards.saveAll(parsed);
    }

    /**
     * Aplica SM-2 simplificado com a {@code quality} (0–5) que o aluno deu pra
     * resposta. Atualiza {@code easeFactor}, {@code intervalDays}, {@code dueAt}.
     */
    @Transactional
    public Flashcard review(UUID flashcardId, int quality) {
        if (quality < 0 || quality > 5) {
            throw new IllegalArgumentException("Quality deve ser 0–5");
        }
        Flashcard f = flashcards.findById(flashcardId).orElseThrow();
        f.setLastReviewedAt(OffsetDateTime.now());

        // SM-2: se quality < 3, reseta repetições e revisa amanhã.
        if (quality < 3) {
            f.setRepetitions(0);
            f.setIntervalDays(1);
        } else {
            int reps = f.getRepetitions() + 1;
            int next = switch (reps) {
                case 1 -> 1;
                case 2 -> 6;
                default -> (int) Math.round(f.getIntervalDays() * f.getEaseFactor());
            };
            f.setRepetitions(reps);
            f.setIntervalDays(next);
        }
        // Atualiza ease factor pelo SM-2 (mínimo 1.3)
        double ef = f.getEaseFactor()
                + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
        f.setEaseFactor(Math.max(1.3, ef));

        f.setDueAt(OffsetDateTime.now().plusDays(f.getIntervalDays()));
        return flashcards.save(f);
    }

    private List<Chunk> sampleChunks(UUID subjectId, int max) {
        // Pega todos os chunks da matéria e baralha pra cobrir o material todo.
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
            // Trunca o último trecho que ultrapassaria o orçamento, em vez de
            // descartá-lo inteiro, pra aproveitar ao máximo a janela.
            String content = c.getContent();
            int remaining = MAX_BODY_CHARS - sb.length();
            if (remaining <= 0) break;
            if (content.length() > remaining) content = content.substring(0, remaining);
            sb.append(content).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Parser simples do formato P:/R:/F: separado por linhas de "---".
     * Robusto a variações de espaçamento e linhas em branco extras.
     */
    private List<Flashcard> parse(String raw, Subject subject) {
        List<Flashcard> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String block : raw.split("(?m)^---\\s*$")) {
            String q = null, a = null, f = null;
            StringBuilder currentValue = new StringBuilder();
            String currentField = null;
            for (String line : block.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("P:")) {
                    flush(currentField, currentValue.toString().trim(), v -> {});
                    currentField = "P"; currentValue.setLength(0);
                    currentValue.append(trimmed.substring(2).trim());
                } else if (trimmed.startsWith("R:")) {
                    if ("P".equals(currentField)) q = currentValue.toString().trim();
                    currentField = "R"; currentValue.setLength(0);
                    currentValue.append(trimmed.substring(2).trim());
                } else if (trimmed.startsWith("F:")) {
                    if ("R".equals(currentField)) a = currentValue.toString().trim();
                    currentField = "F"; currentValue.setLength(0);
                    currentValue.append(trimmed.substring(2).trim());
                } else if (!trimmed.isEmpty() && currentField != null) {
                    currentValue.append(' ').append(trimmed);
                }
            }
            // flush último
            if ("P".equals(currentField)) q = currentValue.toString().trim();
            else if ("R".equals(currentField)) a = currentValue.toString().trim();
            else if ("F".equals(currentField)) f = currentValue.toString().trim();

            if (q != null && !q.isBlank() && a != null && !a.isBlank()) {
                out.add(Flashcard.builder()
                        .subject(subject)
                        .question(q).answer(a)
                        .sourceDoc(f)
                        .build());
            }
        }
        return out;
    }

    private interface ValueConsumer { void accept(String v); }
    private void flush(String field, String value, ValueConsumer ignored) {
        // helper vazio — uso apenas pra clareza do parsing acima
    }
}
