package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

@Entity
@Table(name = "citation")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Citation {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chunk_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Chunk chunk;

    @Column(nullable = false)
    private double relevance;

    @Column(nullable = false, columnDefinition = "text")
    private String excerpt;
}
