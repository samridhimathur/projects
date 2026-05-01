package com.opsbot.repository;

import com.opsbot.model.RcaSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RcaSessionRepository extends JpaRepository<RcaSession, UUID> {
    /*
     * JpaRepository already provides findAll(Pageable) — we just declare
     * the return type explicitly for clarity.
     *
     * Called by SessionService.getRecentSessions() with:
     *   PageRequest.of(0, 20, Sort.by(Direction.DESC, "createdAt"))
     *
     * Spring Data translates this to:
     *   SELECT * FROM rca_sessions ORDER BY created_at DESC LIMIT 20 OFFSET 0
     */
    Page<RcaSession> findAll(Pageable pageable);
}
