package com.acervo.repository;

import com.acervo.domain.Message;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversation_IdOrderByCreatedAtAsc(UUID conversationId);

    @EntityGraph(attributePaths = {"citations", "citations.chunk"})
    @Query("select m from Message m where m.id = :id")
    Optional<Message> findByIdWithCitations(@Param("id") UUID id);
}
