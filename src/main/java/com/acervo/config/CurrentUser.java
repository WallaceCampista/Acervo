package com.acervo.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Acesso ao usuário autenticado no contexto da requisição atual. Helpers para
 * pegar o id ou os detalhes sem replicar a navegação do SecurityContextHolder
 * em vários lugares.
 */
@Component
public class CurrentUser {

    public Optional<AcervoUserDetails> details() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        Object principal = auth.getPrincipal();
        return principal instanceof AcervoUserDetails u ? Optional.of(u) : Optional.empty();
    }

    public Optional<UUID> idOpt() {
        return details().map(AcervoUserDetails::getId);
    }

    /** Lança IllegalStateException se não houver usuário — uso em rotas autenticadas. */
    public UUID id() {
        return idOpt().orElseThrow(() ->
                new IllegalStateException("Sem usuário autenticado no contexto"));
    }
}
