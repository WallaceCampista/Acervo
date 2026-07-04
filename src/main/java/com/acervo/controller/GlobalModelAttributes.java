package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.domain.Subject;
import com.acervo.domain.User;
import com.acervo.service.SubjectService;
import com.acervo.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.UUID;

/**
 * Injeta o usuário corrente em todos os templates renderizados pelos
 * @Controllers, pra o fragment do avatar funcionar sem cada controller
 * precisar adicionar manualmente. O atributo é nomeado {@code profile}
 * por compatibilidade com os templates existentes ({@code profile.fullName()},
 * {@code profile.initials()}). Pode ser {@code null} em rotas públicas.
 *
 * <p>Também provê {@code subjects} e {@code selected} (default = primeira
 * matéria) globalmente, pra a sidebar renderizar a lista de matérias e as
 * ferramentas de estudo em qualquer página. {@link com.acervo.controller.ChatController}
 * e {@link com.acervo.controller.ImportController} ainda sobrescrevem com a
 * matéria escolhida pela query string.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
@RequiredArgsConstructor
public class GlobalModelAttributes {

    private final CurrentUser currentUser;
    private final UserService userService;
    private final SubjectService subjectService;

    @ModelAttribute("profile")
    public User profile() {
        return currentUser.idOpt()
                .flatMap(id -> userService.findById(id))
                .orElse(null);
    }

    @ModelAttribute("isAdmin")
    public boolean isAdmin() {
        return currentUser.idOpt()
                .flatMap(id -> userService.findById(id))
                .map(u -> u.getRole() == User.Role.ADMIN)
                .orElse(false);
    }

    /**
     * Popula {@code subjects} e {@code selected} no model, a menos que o
     * controller já tenha colocado o seu próprio (Chat/Import). Evita query
     * dupla nessas rotas.
     */
    @ModelAttribute
    public void sidebarSubjects(Model model) {
        boolean hasSubjects = model.containsAttribute("subjects");
        boolean hasSelected = model.containsAttribute("selected");
        if (hasSubjects && hasSelected) return;

        UUID userId = currentUser.idOpt().orElse(null);
        if (userId == null) {
            if (!hasSubjects) model.addAttribute("subjects", List.of());
            if (!hasSelected) model.addAttribute("selected", null);
            return;
        }

        List<Subject> subjects = subjectService.findAllForOwner(userId);
        if (!hasSubjects) model.addAttribute("subjects", subjects);
        if (!hasSelected) {
            model.addAttribute("selected", subjects.isEmpty() ? null : subjects.get(0));
        }
    }
}
