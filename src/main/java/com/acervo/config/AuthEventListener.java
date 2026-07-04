package com.acervo.config;

import com.acervo.repository.UserRepository;
import com.acervo.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthEventListener {

    private final AuditService auditService;
    private final UserRepository users;

    @EventListener
    public void onLoginSuccess(AuthenticationSuccessEvent event) {
        Object principal = event.getAuthentication().getPrincipal();
        if (principal instanceof AcervoUserDetails u) {
            auditService.record(u.getId(), "LOGIN_SUCCESS", null);
        }
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        Object name = event.getAuthentication() != null
                ? event.getAuthentication().getName() : null;
        String email = name == null ? "" : name.toString();
        // Tenta vincular ao user quando o email existe pra dar visibilidade
        users.findByEmailIgnoreCase(email).ifPresentOrElse(
                u -> auditService.record(u.getId(), "LOGIN_FAILED", email),
                () -> auditService.record(null, "LOGIN_FAILED", email));
    }
}
