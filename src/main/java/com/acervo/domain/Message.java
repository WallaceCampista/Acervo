package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.*;

@Entity
@Table(name = "message")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Message {

    public enum Role { USER, ASSISTANT }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Role role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    /**
     * Imagem anexada pelo usuário (vision). Guardada como bytea no Postgres.
     * Só presente em mensagens USER que vieram com upload. Servida via
     * {@code GET /chat/messages/{id}/image}.
     */
    @Column(name = "image_data", columnDefinition = "bytea")
    private byte[] imageData;

    @Column(name = "image_mime", length = 64)
    private String imageMime;

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Citation> citations = new ArrayList<>();
}
