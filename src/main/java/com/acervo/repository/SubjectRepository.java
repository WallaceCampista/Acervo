package com.acervo.repository;

import com.acervo.domain.Subject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    List<Subject> findByOwner_IdOrderByNameAsc(UUID ownerId);

    Optional<Subject> findByIdAndOwner_Id(UUID id, UUID ownerId);

    boolean existsByOwner_IdAndNameIgnoreCase(UUID ownerId, String name);
}
