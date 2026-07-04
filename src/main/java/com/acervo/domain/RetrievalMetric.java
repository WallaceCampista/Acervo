package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Snapshot do retrieval em cada pergunta — sinais úteis pra detectar
 * regressões ("muita resposta caindo em no_results", "top1_distance subindo
 * = índice degradado"). Sem dashboard por enquanto; basta a tabela existir
 * pra análise ad-hoc com SQL.
 */
@Entity
@Table(name = "retrieval_metric")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RetrievalMetric {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "question_length", nullable = false)
    private int questionLength;

    @Column(name = "chunks_retrieved", nullable = false)
    private int chunksRetrieved;

    @Column(name = "avg_distance")
    private Double avgDistance;

    @Column(name = "top1_distance")
    private Double top1Distance;

    @Column(name = "no_results", nullable = false)
    @Builder.Default
    private boolean noResults = false;

    @Column(name = "used_lexical_fusion", nullable = false)
    @Builder.Default
    private boolean usedLexicalFusion = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
