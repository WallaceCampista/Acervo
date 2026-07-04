package com.acervo.repository;

import com.acervo.domain.DocumentSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentSummaryRepository extends JpaRepository<DocumentSummary, UUID> {

    Optional<DocumentSummary> findByDocument_IdAndLevel(UUID documentId,
                                                       DocumentSummary.Level level);

    List<DocumentSummary> findByDocument_Id(UUID documentId);
}
