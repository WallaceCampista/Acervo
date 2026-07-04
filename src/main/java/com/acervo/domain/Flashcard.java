package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Flashcard pra revisão espaçada estilo Anki. Campos SM-2 simplificados:
 * {@code easeFactor}, {@code interval} (dias), {@code repetitions}, e
 * {@code dueAt} pro próximo review. O default cobre o estado "novo, nunca
 * revisado".
 */
@Entity
@Table(name = "flashcard", indexes = {
        @Index(name = "idx_flashcard_subject", columnList = "subject_id"),
        @Index(name = "idx_flashcard_due", columnList = "due_at")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Flashcard {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Subject subject;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    /** Documento de origem (nome). Pode ser null se múltiplas fontes. */
    @Column(name = "source_doc", length = 255)
    private String sourceDoc;

    /** Página/slide. Pode ser null. */
    @Column(name = "source_page", length = 60)
    private String sourcePage;

    /** SM-2: fator de facilidade (default 2.5). */
    @Column(name = "ease_factor", nullable = false)
    @Builder.Default
    private double easeFactor = 2.5;

    /** SM-2: intervalo em dias até a próxima revisão. */
    @Column(name = "interval_days", nullable = false)
    @Builder.Default
    private int intervalDays = 0;

    /** SM-2: contagem de revisões consecutivas com qualidade ≥ 3. */
    @Column(nullable = false)
    @Builder.Default
    private int repetitions = 0;

    @Column(name = "due_at", nullable = false)
    @Builder.Default
    private OffsetDateTime dueAt = OffsetDateTime.now();

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "last_reviewed_at")
    private OffsetDateTime lastReviewedAt;
}
