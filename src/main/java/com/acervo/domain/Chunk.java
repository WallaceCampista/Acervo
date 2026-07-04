package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

@Entity
@Table(name = "chunk")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Chunk {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Document document;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "page_label", length = 60)
    private String pageLabel;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;
}
