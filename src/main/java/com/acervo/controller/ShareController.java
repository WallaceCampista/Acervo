package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.domain.Conversation;
import com.acervo.domain.Subject;
import com.acervo.domain.SubjectShare;
import com.acervo.repository.ConversationRepository;
import com.acervo.service.AuditService;
import com.acervo.service.SubjectShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ShareController {

    private final SubjectShareService shareService;
    private final ConversationRepository conversationRepository;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    /**
     * Cria um link de compartilhamento para a matéria.
     */
    @PostMapping("/subjects/{id}/share")
    public String share(@PathVariable UUID id,
                        @RequestParam(required = false) String label) {
        UUID userId = currentUser.id();
        SubjectShare s = shareService.create(id, userId, label);
        auditService.record(userId, "SUBJECT_SHARED",
                "subjectId=" + id + " shareId=" + s.getId());
        return "redirect:/profile?tab=shares&saved";
    }

    /**
     * Revoga um link.
     */
    @PostMapping("/shares/{id}/revoke")
    public String revoke(@PathVariable UUID id) {
        UUID userId = currentUser.id();
        shareService.revoke(id, userId);
        auditService.record(userId, "SHARE_REVOKED", "shareId=" + id);
        return "redirect:/profile?tab=shares&saved";
    }

    /**
     * Página pública (sem auth) que mostra uma matéria compartilhada
     * em modo read-only: lista de documentos + lista de conversas.
     */
    @GetMapping("/shared/{token}")
    public String viewShared(@PathVariable String token, Model model) {
        SubjectShare share = shareService.findByToken(token).orElse(null);
        if (share == null || !share.isActive()) {
            model.addAttribute("error", "Link inválido, revogado ou expirado.");
            return "shared-error";
        }
        Subject subject = share.getSubject();
        List<Conversation> conversations = conversationRepository
                .findBySubject_IdOrderByCreatedAtDesc(subject.getId());
        model.addAttribute("share", share);
        model.addAttribute("subject", subject);
        model.addAttribute("documents", subject.getDocuments());
        model.addAttribute("conversations", conversations);
        return "shared";
    }
}
