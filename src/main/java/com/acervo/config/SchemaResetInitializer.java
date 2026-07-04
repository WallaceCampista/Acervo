package com.acervo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Quando {@code acervo.bootstrap.reset-on-start=true} (ou env
 * {@code ACERVO_BOOTSTRAP_RESET=true}), sobrescreve
 * {@code spring.jpa.hibernate.ddl-auto} para {@code create} — Hibernate dropa
 * o schema inteiro e recria do zero baseado nas entidades.
 *
 * <p>Roda como {@link EnvironmentPostProcessor} (carregado via
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports})
 * porque {@code @ConditionalOnProperty} e {@code @Bean} são tarde demais —
 * a config JPA já leu as propriedades quando esses rodam.
 *
 * <p>⚠ Destrutivo. Use só pra zerar dados de demo/teste; lembre de voltar
 * a flag pra false depois (senão cada boot apaga tudo).
 */
@Slf4j
public class SchemaResetInitializer implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if ("true".equalsIgnoreCase(env.getProperty("acervo.bootstrap.reset-on-start"))) {
            log.warn("==================================================================");
            log.warn(" ACERVO_BOOTSTRAP_RESET=true — DROP+CREATE de todo o schema no boot");
            log.warn(" Todos os dados serão perdidos. Defina false após este startup.");
            log.warn("==================================================================");
            env.getPropertySources().addFirst(new MapPropertySource(
                    "acervo-reset-override",
                    Map.of("spring.jpa.hibernate.ddl-auto", "create")));
        }
    }

    @Override
    public int getOrder() {
        // Roda cedo, mas depois do ConfigDataEnvironmentPostProcessor que
        // carrega application.yml — assim conseguimos ler nossa flag.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
