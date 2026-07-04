package com.acervo.service;

import com.acervo.domain.AuditLog;
import com.acervo.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Registra eventos relevantes pra auditoria. Não-bloqueante: falhas aqui não
 * abortam a operação principal — só logam um warn.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository repository;

    public void record(UUID userId, String action, String payload) {
        try {
            repository.save(AuditLog.builder()
                    .userId(userId)
                    .action(action)
                    .payload(payload)
                    .build());
        } catch (Exception e) {
            log.warn("Falha ao gravar audit_log ({}): {}", action, e.getMessage());
        }
    }

    public List<AuditLog> recentForUser(UUID userId) {
        return repository.findTop50ByUserIdOrderByAtDesc(userId);
    }
}
