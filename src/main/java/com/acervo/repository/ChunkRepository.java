package com.acervo.repository;

import com.acervo.domain.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {
    long countByDocument_Subject_Id(UUID subjectId);
    List<Chunk> findByDocument_Id(UUID documentId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByDocument_Id(UUID documentId);

    /**
     * Busca lexical (BM25-like via Postgres FTS) restrita a uma matéria,
     * ordenada por {@code ts_rank} decrescente. Retorna IDs dos chunks pra
     * uso na fusão Reciprocal Rank com o ranking vetorial.
     *
     * <p>O {@code plainto_tsquery} parseia a query do usuário com tokenização
     * portuguesa — tolera pontuação e palavras vazias sem reclamar.
     */
    @Query(value = """
            SELECT c.id
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE d.subject_id = :subjectId
              AND c.content_tsv @@ plainto_tsquery('portuguese', :query)
            ORDER BY ts_rank(c.content_tsv, plainto_tsquery('portuguese', :query)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findTopByLexicalSearch(@Param("subjectId") UUID subjectId,
                                      @Param("query") String query,
                                      @Param("limit") int limit);
}
