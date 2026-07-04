package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.domain.SubjectShare;
import com.acervo.service.AuditService;
import com.acervo.service.SubjectShareService;
import com.acervo.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;
    private final CurrentUser currentUser;
    private final SubjectShareService shareService;
    private final AuditService auditService;
    private final SessionRegistry sessionRegistry;

    @GetMapping
    public String view(@RequestParam(required = false, defaultValue = "personal") String tab,
                       @RequestParam(required = false) String saved,
                       @RequestParam(required = false) String error,
                       Model model) {
        UUID userId = currentUser.id();
        model.addAttribute("profile", userService.get(userId));
        model.addAttribute("tab", tab);
        if (saved != null) model.addAttribute("saved", true);
        if (error != null) model.addAttribute("error", error);

        if ("shares".equals(tab)) {
            List<SubjectShare> shares = shareService.listForOwner(userId);
            model.addAttribute("shares", shares);
        }
        if ("audit".equals(tab)) {
            model.addAttribute("auditEntries", auditService.recentForUser(userId));
        }
        return "profile";
    }

    @PostMapping
    public String savePersonal(@RequestParam(required = false) String firstName,
                               @RequestParam(required = false) String lastName,
                               @RequestParam(required = false) String contact) {
        userService.updateProfile(currentUser.id(), firstName, lastName, contact);
        return "redirect:/profile?tab=personal&saved";
    }

    @PostMapping("/password")
    public String changePassword(@RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            return "redirect:/profile?tab=security&error=length";
        }
        if (!newPassword.equals(confirmPassword)) {
            return "redirect:/profile?tab=security&error=mismatch";
        }
        UUID userId = currentUser.id();
        boolean ok = userService.changePassword(userId, currentPassword, newPassword);
        if (!ok) {
            auditService.record(userId, "PASSWORD_CHANGE_FAILED", null);
            return "redirect:/profile?tab=security&error=current";
        }
        auditService.record(userId, "PASSWORD_CHANGED", null);
        // Invalida sessões antigas (exceto a atual implicitamente — a atual será
        // recriada via re-autenticação. Aqui expira tudo do SessionRegistry).
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            // SessionRegistryImpl indexa pelo principal serializável (no nosso
            // caso, AcervoUserDetails). Comparamos pelo email.
            if (principal instanceof com.acervo.config.AcervoUserDetails u
                    && u.getId().equals(userId)) {
                List<SessionInformation> sessions = sessionRegistry
                        .getAllSessions(u, false);
                for (SessionInformation s : sessions) {
                    s.expireNow();
                }
            }
        }
        return "redirect:/profile?tab=security&saved";
    }
}
