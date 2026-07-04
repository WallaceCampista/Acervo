package com.acervo.config;

import com.acervo.domain.User;
import com.acervo.repository.UserRepository;
import com.acervo.service.AuditService;
import com.acervo.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Cria a conta {@code root} (ADMIN) na primeira inicialização. Sem ela, um
 * sistema novo ficaria sem nenhum usuário e a única forma de entrar seria
 * pelo signup público — o que pode não ser desejado em deploys controlados.
 *
 * <p>Email e senha vêm de {@code acervo.bootstrap.root-email} e
 * {@code acervo.bootstrap.root-password} (defaults: {@code root@acervo.local}
 * e uma senha aleatória de 16 chars logada uma vez no boot). Em produção
 * sobrescreva via variáveis de ambiente {@code ACERVO_BOOTSTRAP_ROOT_EMAIL}
 * e {@code ACERVO_BOOTSTRAP_ROOT_PASSWORD}.
 *
 * <p>Idempotente: se o user já existe (por email), não faz nada.
 */
@Component
@RequiredArgsConstructor
@Order(10) // depois do SchemaBootstrap (Order=0) — schema deve existir
@Slf4j
public class BootstrapDataInitializer implements ApplicationRunner {

    private final UserRepository users;
    private final UserService userService;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    @Value("${acervo.bootstrap.root-email:root@acervo.local}")
    private String rootEmail;

    @Value("${acervo.bootstrap.root-password:}")
    private String rootPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            log.debug("BootstrapDataInitializer: já existem usuários — pulando.");
            return;
        }
        String password = rootPassword == null || rootPassword.isBlank()
                ? randomPassword(16)
                : rootPassword;
        boolean generated = rootPassword == null || rootPassword.isBlank();

        User root = userService.signup(rootEmail, password, "Root", "Admin", User.Role.ALUNO);
        root.setRole(User.Role.ADMIN);
        users.save(root);
        auditService.record(root.getId(), "ROOT_USER_CREATED",
                "email=" + rootEmail + " generated=" + generated);

        log.info("====================================================================");
        log.info(" Acervo: usuário root criado");
        log.info("   email:    {}", rootEmail);
        if (generated) {
            log.info("   senha:    {}", password);
            log.info("   ↑ senha gerada — anote agora; troque depois em /profile?tab=security.");
            log.info("   Para sobrescrever, defina ACERVO_BOOTSTRAP_ROOT_PASSWORD.");
        } else {
            log.info("   senha:    (lida de acervo.bootstrap.root-password)");
        }
        log.info("====================================================================");
    }

    private String randomPassword(int len) {
        // Alfanumérico, fácil de copiar do log.
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
