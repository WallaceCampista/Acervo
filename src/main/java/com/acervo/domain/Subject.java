package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.*;

@Entity
@Table(name = "subject", uniqueConstraints = {
        @UniqueConstraint(name = "uk_subject_owner_name",
                columnNames = {"owner_id", "name"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Subject {

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * Dono da matéria. Nullable na coluna pra suportar boot em bases legadas
     * sem owner (backfill rodado pelo SchemaBootstrap); operacionalmente
     * todas as criações vindas pela UI já vêm com owner preenchido.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User owner;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 9)
    private String color;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @OneToMany(mappedBy = "subject", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Document> documents = new ArrayList<>();
}
