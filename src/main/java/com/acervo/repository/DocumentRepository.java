package com.acervo.repository;

import com.acervo.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    List<Document> findBySubject_IdOrderByUploadedAtDesc(UUID subjectId);
    List<Document> findBySubject_IdAndSubject_Owner_IdOrderByUploadedAtDesc(
            UUID subjectId, UUID ownerId);
    long countBySubject_Id(UUID subjectId);
    long countBySubject_IdAndStatus(UUID subjectId, Document.Status status);
    long countBySubject_Owner_Id(UUID ownerId);
}
