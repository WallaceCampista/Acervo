package com.acervo.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Roda <em>antes</em> da migração de schema do Hibernate. O Hibernate 6 com
 * {@code ddl-auto: update} gera reiteradamente {@code ALTER COLUMN ... SET
 * DATA TYPE text} em colunas que já estão marcadas como {@code text} via
 * {@code columnDefinition} — bug conhecido com PostgreSQL. O primeiro desses
 * ALTER (em {@code chunk.content}) falha porque o trigger
 * {@code trg_chunk_content_tsv} depende da coluna, e como o ALTER falha em
 * autocommit a conexão é marcada como broken, derrubando o boot inteiro.
 *
 * <p>Solução: derruba o trigger antes da migração do Hibernate. O
 * {@link SchemaBootstrap} recria depois (idempotente).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class PreSchemaMigration {

    private final DataSource dataSource;

    @PostConstruct
    void dropFtsTriggerIfExists() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_chunk_content_tsv ON chunk");
            log.debug("PreSchemaMigration: trigger trg_chunk_content_tsv removido (será recriado pelo SchemaBootstrap).");
        } catch (Exception e) {
            // Tabela chunk pode não existir no primeiríssimo boot — não-bloqueante.
            log.debug("PreSchemaMigration: drop trigger ignorado ({}).", e.getMessage());
        }
    }

    /**
     * Garante que o {@code entityManagerFactory} só seja construído depois
     * que o {@link PreSchemaMigration} já tiver rodado o {@code @PostConstruct}.
     */
    @Configuration
    static class EntityManagerFactoryDependency
            extends EntityManagerFactoryDependsOnPostProcessor {
        EntityManagerFactoryDependency() {
            super("preSchemaMigration");
        }
    }
}
