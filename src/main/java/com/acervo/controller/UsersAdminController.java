package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.domain.User;
import com.acervo.service.AuditService;
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
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class UsersAdminController {

    private final UserService userService;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final SessionRegistry sessionRegistry;

    @GetMapping
    public String list(@RequestParam(required = false) String saved,
                       @RequestParam(required = false) String error,
                       @RequestParam(required = false) String tempPassword,
                       @RequestParam(required = false) String tempEmail,
                       Model model) {
        List<User> all = userService.listAll();
        model.addAttribute("users", all);
        model.addAttribute("roles", User.Role.values());
        model.addAttribute("nav", "users");
        if (saved != null) model.addAttribute("saved", saved);
        if (error != null) model.addAttribute("error", error);
        if (tempPassword != null) {
            model.addAttribute("tempPassword", tempPassword);
            model.addAttribute("tempEmail", tempEmail);
        }
        return "admin-users";
    }

    @PostMapping("/create")
    public String create(@RequestParam String email,
                         @RequestParam String password,
                         @RequestParam(required = false, defaultValue = "") String firstName,
                         @RequestParam(required = false, defaultValue = "") String lastName,
                         @RequestParam(required = false, defaultValue = "ALUNO") String role) {
        String normalized = UserService.normalizeEmail(email);
        if (normalized.isBlank() || password == null || password.length() < 6) {
            return "redirect:/admin/users?error=invalid";
        }
        User.Role parsedRole;
        try {
            parsedRole = User.Role.valueOf(role.toUpperCase());
        } catch (Exception e) {
            parsedRole = User.Role.ALUNO;
        }
        try {
            User created = userService.signup(normalized, password, firstName, lastName, parsedRole);
            auditService.record(currentUser.id(), "ADMIN_USER_CREATED",
                    "target=" + created.getId() + " email=" + created.getEmail() +
                    " role=" + created.getRole());
            return "redirect:/admin/users?saved=created";
        } catch (UserService.EmailAlreadyInUseException ex) {
            return "redirect:/admin/users?error=email";
        }
    }

    @PostMapping("/{id}/toggle")
    public String toggleActive(@PathVariable UUID id) {
        UUID me = currentUser.id();
        if (me.equals(id)) {
            return "redirect:/admin/users?error=self";
        }
        User u = userService.get(id);
        boolean next = !u.isActive();
        userService.setActive(id, next);
        if (!next) {
            expireSessions(id);
        }
        auditService.record(me, next ? "ADMIN_USER_ACTIVATED" : "ADMIN_USER_DEACTIVATED",
                "target=" + id);
        return "redirect:/admin/users?saved=" + (next ? "activated" : "deactivated");
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable UUID id) {
        String temp = generateTempPassword();
        User u = userService.resetPassword(id, temp);
        expireSessions(id);
        auditService.record(currentUser.id(), "ADMIN_PASSWORD_RESET", "target=" + id);
        return "redirect:/admin/users?saved=reset"
                + "&tempPassword=" + temp
                + "&tempEmail=" + u.getEmail();
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable UUID id,
                         @RequestParam(required = false) String firstName,
                         @RequestParam(required = false) String lastName,
                         @RequestParam(required = false) String contact,
                         @RequestParam(required = false) String role) {
        User.Role parsedRole = null;
        if (role != null && !role.isBlank()) {
            try {
                parsedRole = User.Role.valueOf(role.toUpperCase());
            } catch (Exception ignore) {}
        }
        userService.adminUpdate(id, firstName, lastName, contact, parsedRole);
        auditService.record(currentUser.id(), "ADMIN_USER_UPDATED", "target=" + id);
        return "redirect:/admin/users?saved=updated";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id) {
        UUID me = currentUser.id();
        if (me.equals(id)) {
            return "redirect:/admin/users?error=self";
        }
        expireSessions(id);
        userService.delete(id);
        auditService.record(me, "ADMIN_USER_DELETED", "target=" + id);
        return "redirect:/admin/users?saved=deleted";
    }

    private void expireSessions(UUID userId) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof com.acervo.config.AcervoUserDetails u
                    && u.getId().equals(userId)) {
                List<SessionInformation> sessions = sessionRegistry.getAllSessions(u, false);
                for (SessionInformation s : sessions) {
                    s.expireNow();
                }
            }
        }
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static String generateTempPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
