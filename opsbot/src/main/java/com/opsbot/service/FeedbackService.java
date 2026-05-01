package com.opsbot.service;

import com.opsbot.model.RcaFeedback;
import com.opsbot.model.RcaSession;
import com.opsbot.repository.RcaFeedbackRepository;
import com.opsbot.repository.RcaSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final RcaFeedbackRepository feedbackRepository;
    private final RcaSessionRepository sessionRepository;

    public FeedbackService(RcaFeedbackRepository feedbackRepository,
                           RcaSessionRepository sessionRepository) {
        this.feedbackRepository = feedbackRepository;
        this.sessionRepository = sessionRepository;
    }

    /*
     * Saves engineer feedback for a completed RCA session.
     *
     * Flow:
     * 1. Find the session by ID — fail fast if not found
     * 2. Validate session is COMPLETE — cannot rate a PENDING session
     * 3. Save the feedback record
     * 4. Update session status to REVIEWED
     *
     * Why Mono.fromCallable + subscribeOn(boundedElastic)?
     * Both JPA calls (findById, save) are blocking. We must offload
     * them to the bounded elastic thread pool so we never block
     * a reactive Netty thread. This is the correct pattern for
     * mixing JPA with WebFlux.
     *
     * MATURE ALTERNATIVES:
     * - R2DBC: truly non-blocking DB — eliminates need for boundedElastic
     * - Spring Data R2DBC repositories return Mono/Flux natively
     * - Trade-off: R2DBC loses JPA features (lazy loading, @OneToMany etc)
     */
    public Mono<RcaFeedback> submitFeedback(UUID sessionId, Short rating, String comment) {
        return Mono.fromCallable(() -> {

            // Step 1 — find session, throw if not found
            RcaSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Session not found: " + sessionId));

            // Step 2 — only allow feedback on completed sessions
            if (session.getStatus() == RcaSession.SessionStatus.PENDING) {
                throw new IllegalStateException(
                        "Cannot submit feedback for a PENDING session. " +
                        "Wait for the RCA to complete first.");
            }

            // Step 3 — build and save feedback
            RcaFeedback feedback = new RcaFeedback();
            feedback.setSession(session);
            feedback.setRating(rating);
            feedback.setComment(comment);
            RcaFeedback saved = feedbackRepository.save(feedback);

            // Step 4 — mark session as REVIEWED
            session.setStatus(RcaSession.SessionStatus.REVIEWED);
            sessionRepository.save(session);

            log.info("Feedback saved for session={} rating={}", sessionId, rating);
            return saved;

        }).subscribeOn(Schedulers.boundedElastic());
    }
}
