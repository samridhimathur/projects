package com.opsbot.repository;

import com.opsbot.model.RunbookChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RunbookRepository extends JpaRepository<RunbookChunk, Long> {

    /*
     * NATIVE pgvector similarity search.
     *
     * Why @Query with nativeQuery = true?
     * JPQL (the default JPA query language) has no concept of the <=> operator.
     * It only understands standard SQL operations. pgvector's cosine distance
     * operator <=> is Postgres-specific, so we must drop down to native SQL.
     *
     * The query:
     *   embedding <=> CAST(:embedding AS vector)
     *
     * <=>  = cosine distance operator (pgvector)
     *       0.0 = identical vectors (perfect match)
     *       1.0 = completely different vectors
     *       ORDER BY ASC = most similar first
     *
     * CAST(:embedding AS vector) — Spring passes the float[] as a string
     * representation "[0.1, 0.2, ...]". The CAST converts it to pgvector's
     * vector type so the <=> operator can work on it.
     *
     * LIMIT 5 — we inject top 5 chunks into the Claude prompt (our decision).
     */
    @Query(
            value = """
            SELECT *
            FROM runbook_chunks
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT 5
            """,
            nativeQuery = true
    )
    List<RunbookChunk> findTopSimilarChunks(@Param("embedding") String embedding);

    boolean existsBySourceAndChunkIndex(String source, Integer chunkIndex);
}