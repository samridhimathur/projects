package com.opsbot.repository;

import com.opsbot.model.RcaSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RcaSessionRepository extends JpaRepository<RcaSession, UUID> {
    // Spring Data generates all CRUD queries automatically.
    // Custom queries will be added in Week 3 for RAG session lookup.
}
