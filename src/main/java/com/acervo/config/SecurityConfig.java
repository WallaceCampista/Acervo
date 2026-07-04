package com.acervo.config;

import com.acervo.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuditService auditService;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Publica HttpSessionEvent pra alimentar o SessionRegistry — sem isso,
     * sessões expiradas não somem do registro e o "invalidar sessões antigas"
     * (ao trocar de senha) não funciona.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .csrf(csrf -> csrf
                    // Token CSRF salvo na sessão (default). Mais simples e
                    // funciona out-of-the-box com MockMvc + .with(csrf()).
                    // Actuator é métrica externa (Prometheus/healthcheck) — exclui CSRF.
                    .csrfTokenRequestHandler(csrfHandler)
                    .ignoringRequestMatchers("/actuator/**",
                            // SSE não recebe CSRF token do browser via header;
                            // o endpoint é GET, então é seguro ignorar.
                            "/chat/conversations/*/stream"))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(
                            "/landing", "/login", "/signup",
                            "/shared/**",
                            "/css/**", "/js/**", "/images/**",
                            "/webjars/**", "/actuator/**", "/favicon.ico",
                            "/error"
                    ).permitAll()
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated())
            .formLogin(form -> form
                    .loginPage("/landing")
                    .loginProcessingUrl("/login")
                    .usernameParameter("email")
                    .passwordParameter("password")
                    .defaultSuccessUrl("/inicio", true)
                    .failureUrl("/landing?error")
                    .permitAll())
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/landing?logout")
                    .deleteCookies("JSESSIONID")
                    .addLogoutHandler(logoutAuditHandler())
                    .permitAll())
            .sessionManagement(sm -> sm
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .exceptionHandling(ex -> ex
                    .accessDeniedPage("/landing?denied"));

        return http.build();
    }

    private LogoutHandler logoutAuditHandler() {
        return (request, response, authentication) -> {
            if (authentication != null
                    && authentication.getPrincipal() instanceof AcervoUserDetails u) {
                auditService.record(u.getId(), "LOGOUT", null);
            }
        };
    }
}
