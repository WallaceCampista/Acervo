package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registro append-only de ações relevantes (login, mudança de senha, criação
 * e exclusão de matéria, compartilhamentos). Útil pra debug e pra LGPD.
 *
 * <p>userId é nullable porque alguns eventos (ex.: tentativa de login com
 * email inexistente) não têm conta associada.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_user", columnList = "user_id"),
        @Index(name = "idx_audit_at",   columnList = "at")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "at", nullable = false)
    @Builder.Default
    private OffsetDateTime at = OffsetDateTime.now();
}
