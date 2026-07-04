package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Link compartilhado pra acesso read-only a uma matéria. O token é a parte
 * "secreta" da URL — sem ele não há como descobrir a existência do share.
 * {@code revokedAt} marca quando o dono cortou o acesso (sem deletar o registro
 * pra manter histórico). {@code expiresAt} é opcional pra shares limitados
 * no tempo.
 */
@Entity
@Table(name = "subject_share", indexes = {
        @Index(name = "idx_subject_share_token", columnList = "token", unique = true),
        @Index(name = "idx_subject_share_subject", columnList = "subject_id")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SubjectShare {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Subject subject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User createdBy;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(length = 120)
    private String label;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    public boolean isActive() {
        if (revokedAt != null) return false;
        return expiresAt == null || expiresAt.isAfter(OffsetDateTime.now());
    }
}
