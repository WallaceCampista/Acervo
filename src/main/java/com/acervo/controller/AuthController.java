package com.acervo.controller;

import com.acervo.domain.User;
import com.acervo.service.AuditService;
import com.acervo.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final AuditService auditService;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    @GetMapping("/landing")
    public String landing(@RequestParam(required = false) String error,
                          @RequestParam(required = false) String logout,
                          @RequestParam(required = false) String denied,
                          @RequestParam(required = false) String registered,
                          Model model) {
        if (error != null) model.addAttribute("loginError", "Email ou senha inválidos.");
        if (logout != null) model.addAttribute("info", "Você saiu da sua conta.");
        if (denied != null) model.addAttribute("info", "Acesso negado.");
        if (registered != null) model.addAttribute("info", "Conta criada — entre com seu email e senha.");
        return "landing";
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@RequestParam String email,
                         @RequestParam String password,
                         @RequestParam(required = false, defaultValue = "") String firstName,
                         @RequestParam(required = false, defaultValue = "") String lastName,
                         @RequestParam(required = false, defaultValue = "ALUNO") String role,
                         HttpServletRequest request,
                         HttpServletResponse response,
                         RedirectAttributes redirect,
                         Model model) {
        String normalizedEmail = UserService.normalizeEmail(email);
        if (normalizedEmail.isBlank() || password == null || password.length() < 6) {
            model.addAttribute("signupError",
                    "Informe um email válido e senha com pelo menos 6 caracteres.");
            model.addAttribute("email", email);
            model.addAttribute("firstName", firstName);
            model.addAttribute("lastName", lastName);
            return "signup";
        }
        User.Role parsedRole;
        try {
            parsedRole = User.Role.valueOf(role.toUpperCase());
        } catch (Exception e) {
            parsedRole = User.Role.ALUNO;
        }
        try {
            User user = userService.signup(normalizedEmail, password,
                    firstName, lastName, parsedRole);
            auditService.record(user.getId(), "SIGNUP", "role=" + user.getRole());
            // Auto-login
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, password));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            auditService.record(user.getId(), "LOGIN_SUCCESS", "post-signup");
            return "redirect:/inicio";
        } catch (UserService.EmailAlreadyInUseException ex) {
            model.addAttribute("signupError", "Esse email já está cadastrado.");
            model.addAttribute("email", email);
            model.addAttribute("firstName", firstName);
            model.addAttribute("lastName", lastName);
            return "signup";
        }
    }
}
