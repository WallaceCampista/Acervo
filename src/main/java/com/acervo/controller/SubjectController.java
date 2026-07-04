package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.domain.Subject;
import com.acervo.service.AuditService;
import com.acervo.service.SubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Controller
@RequestMapping("/subjects")
@RequiredArgsConstructor
public class SubjectController {

    private final SubjectService subjectService;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    @PostMapping
    public String create(@RequestParam String name) {
        UUID userId = currentUser.id();
        Subject created = subjectService.create(name, userId);
        auditService.record(userId, "SUBJECT_CREATED",
                "subjectId=" + created.getId() + " name=" + created.getName());
        return "redirect:/import";
    }

    @PostMapping("/{id}/rename")
    public String rename(@PathVariable UUID id, @RequestParam String name) {
        UUID userId = currentUser.id();
        subjectService.rename(id, name, userId);
        auditService.record(userId, "SUBJECT_RENAMED",
                "subjectId=" + id + " name=" + name);
        return "redirect:/import?subject=" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id) {
        UUID userId = currentUser.id();
        subjectService.delete(id, userId);
        auditService.record(userId, "SUBJECT_DELETED", "subjectId=" + id);
        return "redirect:/import";
    }
}
