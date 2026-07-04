package com.acervo.repository;

import com.acervo.domain.QuizQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, UUID> {

    List<QuizQuestion> findBySubject_IdOrderByCreatedAtDesc(UUID subjectId);

    long countBySubject_Id(UUID subjectId);
}
