package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.domain.Document;
import com.acervo.domain.Subject;
import com.acervo.service.DocumentService;
import com.acervo.service.SubjectService;
import com.acervo.service.SummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Wizard de Resumo: matéria → documento → tipo de resumo. Reaproveita
 * {@link SummaryService} e a página de geração existente — só agrupa o fluxo
 * num único lugar acessível pela sidebar.
 */
@Controller
@RequestMapping("/resumo")
@RequiredArgsConstructor
public class ResumoController {

    private final SubjectService subjectService;
    private final DocumentService documentService;
    private final SummaryService summaryService;
    private final CurrentUser currentUser;

    @GetMapping
    public String view(@RequestParam(required = false) UUID subject,
                       @RequestParam(required = false) UUID doc,
                       Model model) {
        UUID userId = currentUser.id();
        List<Subject> subjects = subjectService.findAllForOwner(userId);

        Subject selectedSubject = subject == null ? null
                : subjects.stream().filter(s -> s.getId().equals(subject))
                          .findFirst().orElse(null);

        List<Document> docs = selectedSubject == null ? List.of()
                : documentService.listBySubjectForOwner(selectedSubject.getId(), userId)
                                 .stream()
                                 .filter(d -> d.getStatus() == Document.Status.INDEXED)
                                 .toList();

        Document selectedDoc = doc == null ? null
                : docs.stream().filter(d -> d.getId().equals(doc))
                      .findFirst().orElse(null);

        model.addAttribute("nav", "resumo");
        model.addAttribute("subjects", subjects);
        model.addAttribute("selectedSubject", selectedSubject);
        // selected também — usado pela sidebar pra mostrar ferramentas de estudo
        model.addAttribute("selected", selectedSubject);
        model.addAttribute("documents", docs);
        model.addAttribute("selectedDoc", selectedDoc);
        model.addAttribute("summaries",
                selectedDoc == null ? List.of()
                        : summaryService.listForDocument(selectedDoc.getId()));
        return "resumo";
    }
}
