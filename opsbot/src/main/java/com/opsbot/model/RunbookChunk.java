package com.opsbot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/*
 * Maps to the runbook_chunks table created in init.sql.
 * The embedding column is float[] — pgvector stores it as vector(1024).
 * We use a custom UserType via @Column(columnDefinition) to tell
 * Hibernate exactly how to handle the vector type.
 */
@Entity
@Table(name = "runbook_chunks")
@Data
@NoArgsConstructor
public class RunbookChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String source;          // filename e.g. "high-memory-runbook.txt"

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;     // position within the file (0, 1, 2...)

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;         // raw text of this chunk

    /*
     * The vector column.
     * @Column(columnDefinition = "vector(1024)") tells Hibernate the exact
     * Postgres type. Without this it would try to map float[] as a numeric
     * array, which is a different type in Postgres and causes a type mismatch.
     *
     * We store it as float[] in Java — pgvector's JDBC driver handles the
     * conversion between float[] and the vector type on the wire.
     */
    @Column(columnDefinition = "vector(1024)")
    private float[] embedding;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}