package com.acervo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Aplica DDLs que o Hibernate {@code ddl-auto: update} <strong>não</strong>
 * consegue gerenciar a partir das entidades JPA: extensão {@code vector},
 * coluna {@code chunk.content_tsv} computada (GENERATED ALWAYS) e índices
 * GIN do FTS.
 *
 * <p>Tudo {@code IF NOT EXISTS} — pode rodar quantas vezes precisar sem
 * efeito colateral. Sem Flyway, esse bootstrap substitui as migrations.
 */
@Component
@RequiredArgsConstructor
@Order(0)
@Slf4j
public class SchemaBootstrap implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        log.info("SchemaBootstrap: aplicando DDLs complementares...");

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

        // Adopt-legacy-data: se existem matérias sem owner e existe ao menos
        // um usuário cadastrado, adota tudo pro mais antigo. Garante que dados
        // pré-Fase-4 (single-user) não fiquem inacessíveis após habilitar o
        // multiusuário. Idempotente — só roda quando há órfãos.
        try {
            Integer orphanCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM subject WHERE owner_id IS NULL",
                    Integer.class);
            if (orphanCount != null && orphanCount > 0) {
                jdbc.queryForList(
                        "SELECT id FROM app_user ORDER BY created_at ASC LIMIT 1")
                    .stream().findFirst()
                    .ifPresentOrElse(row -> {
                        UUID ownerId = (UUID) row.get("id");
                        int updated = jdbc.update(
                                "UPDATE subject SET owner_id = ? WHERE owner_id IS NULL",
                                ownerId);
                        log.info("SchemaBootstrap: {} matérias órfãs adotadas pelo usuário {}",
                                updated, ownerId);
                    }, () -> log.warn(
                        "SchemaBootstrap: {} matérias sem owner e nenhum usuário " +
                        "cadastrado — o primeiro signup as adotará automaticamente.",
                        orphanCount));
            }
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // Tabelas ainda não existem (1º boot do banco) — Hibernate as cria
            // depois. Não-bloqueante.
            log.debug("SchemaBootstrap: tabelas ainda não criadas, pulando adoção.");
        }

        // FTS lexical em portugues sobre conteúdo dos chunks (hybrid search).
        // Coluna `tsvector` normal + trigger que mantém em sincronia com `content`.
        // Não usamos `GENERATED ALWAYS` porque ele trava ALTER TABLE em `content`,
        // que o Hibernate `ddl-auto: update` reaplica a cada startup.
        jdbc.execute("""
            ALTER TABLE chunk
            ADD COLUMN IF NOT EXISTS content_tsv tsvector
            """);
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION update_chunk_content_tsv()
            RETURNS trigger AS $$
            BEGIN
                NEW.content_tsv := to_tsvector('portuguese', NEW.content);
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_chunk_content_tsv ON chunk");
        jdbc.execute("""
            CREATE TRIGGER trg_chunk_content_tsv
            BEFORE INSERT OR UPDATE OF content ON chunk
            FOR EACH ROW EXECUTE FUNCTION update_chunk_content_tsv()
            """);
        // Backfill pros chunks já existentes (idempotente)
        jdbc.execute("""
            UPDATE chunk
            SET content_tsv = to_tsvector('portuguese', content)
            WHERE content_tsv IS NULL
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_chunk_content_tsv
            ON chunk USING GIN (content_tsv)
            """);

        log.info("SchemaBootstrap: pronto.");
    }
}
