package com.acervo.repository;

import com.acervo.domain.SubjectShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubjectShareRepository extends JpaRepository<SubjectShare, UUID> {

    Optional<SubjectShare> findByToken(String token);

    List<SubjectShare> findBySubject_IdOrderByCreatedAtDesc(UUID subjectId);

    List<SubjectShare> findByCreatedBy_IdOrderByCreatedAtDesc(UUID userId);
}
