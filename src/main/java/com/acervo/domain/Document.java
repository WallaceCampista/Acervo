package com.acervo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.*;

@Entity
@Table(name = "document")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Document {

    public enum Status { PROCESSING, INDEXED, FAILED, CANCELLED }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Subject subject;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "stored_path", nullable = false, length = 512)
    private String storedPath;

    @Column(nullable = false, length = 10)
    private String extension;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    private Integer pages;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PROCESSING;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "uploaded_at", nullable = false)
    @Builder.Default
    private OffsetDateTime uploadedAt = OffsetDateTime.now();

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Chunk> chunks = new ArrayList<>();
}
