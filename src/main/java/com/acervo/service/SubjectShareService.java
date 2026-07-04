package com.acervo.service;

import com.acervo.domain.Subject;
import com.acervo.domain.SubjectShare;
import com.acervo.domain.User;
import com.acervo.repository.SubjectShareRepository;
import com.acervo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubjectShareService {

    private final SubjectShareRepository shares;
    private final SubjectService subjectService;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public SubjectShare create(UUID subjectId, UUID ownerId, String label) {
        Subject subject = subjectService.findByIdForOwner(subjectId, ownerId);
        User owner = users.findById(ownerId).orElseThrow();
        SubjectShare share = SubjectShare.builder()
                .subject(subject)
                .createdBy(owner)
                .token(generateToken())
                .label(label == null ? null : label.trim())
                .build();
        return shares.save(share);
    }

    public List<SubjectShare> listForOwner(UUID ownerId) {
        return shares.findByCreatedBy_IdOrderByCreatedAtDesc(ownerId);
    }

    public List<SubjectShare> listForSubject(UUID subjectId) {
        return shares.findBySubject_IdOrderByCreatedAtDesc(subjectId);
    }

    public Optional<SubjectShare> findByToken(String token) {
        return shares.findByToken(token);
    }

    @Transactional
    public void revoke(UUID shareId, UUID ownerId) {
        SubjectShare s = shares.findById(shareId).orElseThrow();
        if (s.getCreatedBy() == null || !s.getCreatedBy().getId().equals(ownerId)) {
            throw new IllegalStateException("Compartilhamento não pertence ao usuário");
        }
        if (s.getRevokedAt() == null) {
            s.setRevokedAt(OffsetDateTime.now());
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
