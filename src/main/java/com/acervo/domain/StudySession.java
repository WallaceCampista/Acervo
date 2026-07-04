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
 * Sessão de "estudo dirigido": o sistema pergunta, o aluno responde, o sistema
 * avalia e avança/aprofunda. Cada turno é uma {@link StudyTurn}; o estado vive
 * na própria sessão (turn_count, last_topic).
 */
@Entity
@Table(name = "study_session", indexes = {
        @Index(name = "idx_study_subject", columnList = "subject_id"),
        @Index(name = "idx_study_user", columnList = "user_id")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StudySession {

    public enum Status { ACTIVE, FINISHED }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Subject subject;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "last_activity_at", nullable = false)
    @Builder.Default
    private OffsetDateTime lastActivityAt = OffsetDateTime.now();

    @Column(name = "turn_count", nullable = false)
    @Builder.Default
    private int turnCount = 0;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<StudyTurn> turns = new ArrayList<>();
}
