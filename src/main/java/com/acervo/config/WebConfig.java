package com.acervo.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Configuration
public class WebConfig {

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource src = new ReloadableResourceBundleMessageSource();
        src.setBasename("classpath:messages");
        src.setDefaultEncoding(StandardCharsets.UTF_8.name());
        src.setUseCodeAsDefaultMessage(true);
        src.setFallbackToSystemLocale(false);
        return src;
    }

    @Bean
    public LocaleResolver localeResolver() {
        return new FixedLocaleResolver(Locale.of("pt", "BR"));
    }

    /**
     * Habilita @Timed em métodos comuns (não só beans de actuator).
     * Sem esse aspect, anotações @Timed em services seriam ignoradas.
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
