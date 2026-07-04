package com.acervo.repository;

import com.acervo.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    List<Conversation> findBySubject_IdOrderByCreatedAtDesc(UUID subjectId);
    List<Conversation> findBySubject_IdAndSubject_Owner_IdOrderByCreatedAtDesc(
            UUID subjectId, java.util.UUID ownerId);
    long countBySubject_Owner_Id(UUID ownerId);
}
