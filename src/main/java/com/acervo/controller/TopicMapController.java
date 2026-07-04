package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.domain.Subject;
import com.acervo.service.SubjectService;
import com.acervo.service.TopicMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class TopicMapController {

    private final TopicMapService topicMapService;
    private final SubjectService subjectService;
    private final CurrentUser currentUser;

    @GetMapping("/subjects/{id}/topics")
    public String view(@PathVariable UUID id, Model model) {
        UUID userId = currentUser.id();
        Subject subject = subjectService.findByIdForOwner(id, userId);
        model.addAttribute("subjects", subjectService.findAllForOwner(userId));
        model.addAttribute("subject", subject);
        model.addAttribute("selected", subject);
        model.addAttribute("nav", "tools");
        model.addAttribute("tool", "topics");
        try {
            model.addAttribute("topicMap", topicMapService.getOrGenerate(id));
        } catch (Exception e) {
            model.addAttribute("topicMapError", e.getMessage());
        }
        return "topics";
    }

    @PostMapping("/subjects/{id}/topics/regenerate")
    public String regenerate(@PathVariable UUID id) {
        UUID userId = currentUser.id();
        subjectService.findByIdForOwner(id, userId);
        topicMapService.invalidate(id);
        return "redirect:/subjects/" + id + "/topics";
    }
}
