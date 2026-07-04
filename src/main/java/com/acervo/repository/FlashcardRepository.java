package com.acervo.repository;

import com.acervo.domain.Flashcard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface FlashcardRepository extends JpaRepository<Flashcard, UUID> {

    List<Flashcard> findBySubject_IdOrderByCreatedAtDesc(UUID subjectId);

    List<Flashcard> findBySubject_IdAndDueAtLessThanEqualOrderByDueAtAsc(
            UUID subjectId, OffsetDateTime now);

    long countBySubject_Id(UUID subjectId);

    long countBySubject_IdAndDueAtLessThanEqual(UUID subjectId, OffsetDateTime now);
}
