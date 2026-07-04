package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Conta de usuário com email + senha (bcrypt). Substitui o antigo
 * {@code Profile} singleton — agora cada usuário tem o próprio acervo de
 * matérias, documentos e conversas.
 */
@Entity
@Table(name = "app_user", indexes = {
        @Index(name = "idx_app_user_email", columnList = "email", unique = true)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    public enum Role { ALUNO, PROFESSOR, ADMIN }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 160)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 80)
    @Builder.Default
    private String firstName = "";

    @Column(name = "last_name", nullable = false, length = 80)
    @Builder.Default
    private String lastName = "";

    @Column(nullable = false, length = 160)
    @Builder.Default
    private String contact = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.ALUNO;

    @Column(nullable = false, columnDefinition = "boolean not null default true")
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public String initials() {
        String a = firstName != null && !firstName.isBlank()
                ? firstName.substring(0, 1).toUpperCase()
                : "";
        String b = lastName != null && !lastName.isBlank()
                ? lastName.substring(0, 1).toUpperCase()
                : "";
        if (a.isEmpty() && b.isEmpty()) {
            return email != null && !email.isBlank()
                    ? email.substring(0, 1).toUpperCase() : "A";
        }
        return a + b;
    }

    public String fullName() {
        String fn = firstName != null ? firstName.trim() : "";
        String ln = lastName != null ? lastName.trim() : "";
        if (fn.isEmpty() && ln.isEmpty()) return email != null ? email : "Usuário";
        return (fn + " " + ln).trim();
    }
}
