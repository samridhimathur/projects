package com.opsbot.repository;

import com.opsbot.model.RcaFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RcaFeedbackRepository extends JpaRepository<RcaFeedback, Long> {

    /*
     * Spring Data generates this query automatically from the method name.
     * findBy + SessionId → SELECT * FROM rca_feedback WHERE session_id = ?
     * No @Query needed — naming convention handles it.
     */
    List<RcaFeedback> findBySessionId(UUID sessionId);
}
