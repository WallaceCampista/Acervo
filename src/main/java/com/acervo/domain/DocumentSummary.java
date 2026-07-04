package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resumo gerado por LLM para um documento. Três níveis (parágrafo, página,
 * mapa mental); cada documento pode ter no máximo um resumo por nível
 * (unique constraint). Regenerar substitui o existente.
 */
@Entity
@Table(name = "document_summary", uniqueConstraints = {
        @UniqueConstraint(name = "uk_doc_summary_doc_level",
                columnNames = {"document_id", "level"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class DocumentSummary {

    public enum Level { PARAGRAFO, PAGINA, MAPA_MENTAL }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Level level;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "generated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime generatedAt = OffsetDateTime.now();
}
