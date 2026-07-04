package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Questão de quiz com 4 alternativas + índice da correta + explicação. As
 * alternativas são armazenadas como linhas separadas por {@code \n} em
 * {@code optionsText} pra simplificar (sem JSONB).
 */
@Entity
@Table(name = "quiz_question", indexes = {
        @Index(name = "idx_quiz_subject", columnList = "subject_id")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class QuizQuestion {

    public enum Difficulty { EASY, MEDIUM, HARD }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Subject subject;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    /** Uma alternativa por linha. Sempre 4 linhas. */
    @Column(name = "options_text", nullable = false, columnDefinition = "text")
    private String optionsText;

    /** Índice (0–3) da alternativa correta. */
    @Column(name = "correct_index", nullable = false)
    private int correctIndex;

    @Column(columnDefinition = "text")
    private String explanation;

    @Column(name = "source_doc", length = 255)
    private String sourceDoc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Difficulty difficulty = Difficulty.MEDIUM;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public List<String> options() {
        if (optionsText == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : optionsText.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }
}
