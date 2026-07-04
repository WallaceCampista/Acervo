package com.acervo.service;

import com.acervo.domain.Subject;
import com.acervo.domain.User;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.DocumentRepository;
import com.acervo.repository.SubjectRepository;
import com.acervo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SubjectService {

    private static final String[] PALETTE = {
        "#d8a657", "#7fae8f", "#c98b6b", "#9a8fc4", "#6c94d8", "#c4938f"
    };

    private final SubjectRepository subjects;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final UserRepository userRepository;

    public List<Subject> findAllForOwner(UUID ownerId) {
        return subjects.findByOwner_IdOrderByNameAsc(ownerId);
    }

    public Subject findById(UUID id) {
        return subjects.findById(id).orElseThrow();
    }

    public Subject findByIdForOwner(UUID id, UUID ownerId) {
        return subjects.findByIdAndOwner_Id(id, ownerId)
                .orElseThrow(() -> new SubjectAccessDeniedException(id));
    }

    public Optional<Subject> findByIdForOwnerOpt(UUID id, UUID ownerId) {
        return subjects.findByIdAndOwner_Id(id, ownerId);
    }

    @Transactional
    public Subject create(String name, UUID ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalStateException("Owner inexistente: " + ownerId));
        int idx = (int) (subjects.findByOwner_IdOrderByNameAsc(ownerId).size() % PALETTE.length);
        Subject s = Subject.builder()
                .name(name.trim())
                .color(PALETTE[idx])
                .owner(owner)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return subjects.save(s);
    }

    @Transactional
    public Subject rename(UUID id, String newName, UUID ownerId) {
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Nome da matéria não pode ser vazio");
        }
        if (trimmed.length() > 120) {
            trimmed = trimmed.substring(0, 120);
        }
        Subject s = findByIdForOwner(id, ownerId);
        if (!s.getName().equalsIgnoreCase(trimmed)
                && subjects.existsByOwner_IdAndNameIgnoreCase(ownerId, trimmed)) {
            throw new IllegalArgumentException(
                "Já existe uma matéria chamada \"" + trimmed + "\"");
        }
        s.setName(trimmed);
        s.setUpdatedAt(OffsetDateTime.now());
        return s;
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        Subject s = findByIdForOwner(id, ownerId);
        subjects.delete(s);
    }

    public long documentCount(UUID subjectId) {
        return documents.countBySubject_Id(subjectId);
    }

    public long chunkCount(UUID subjectId) {
        return chunks.countByDocument_Subject_Id(subjectId);
    }

    /**
     * Lançado quando o usuário tenta acessar uma matéria que não é dele
     * (ou inexistente). Resulta em 404 — não distingue "não existe" de
     * "não é seu" pra não vazar IDs.
     */
    public static class SubjectAccessDeniedException extends RuntimeException {
        public SubjectAccessDeniedException(UUID id) {
            super("Matéria não acessível: " + id);
        }
    }
}
