package com.acervo.repository;

import com.acervo.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findTop50ByUserIdOrderByAtDesc(UUID userId);
}
