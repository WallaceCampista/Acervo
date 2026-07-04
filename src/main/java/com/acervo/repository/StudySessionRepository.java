package com.acervo.repository;

import com.acervo.domain.StudySession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StudySessionRepository extends JpaRepository<StudySession, UUID> {
    List<StudySession> findBySubject_IdAndUserIdOrderByStartedAtDesc(
            UUID subjectId, UUID userId);
}
