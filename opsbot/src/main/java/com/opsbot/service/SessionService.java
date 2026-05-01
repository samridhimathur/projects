package com.opsbot.service;

import com.opsbot.model.RcaSession;
import com.opsbot.repository.RcaSessionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@Service
public class SessionService {

    private final RcaSessionRepository sessionRepository;

    public SessionService(RcaSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /*
     * Returns the 20 most recent sessions, newest first.
     * Used by the frontend to populate the session history table.
     *
     * Flux.fromIterable() converts the blocking List result
     * into a reactive Flux so the controller can return it directly.
     *
     * PageRequest.of(0, 20, Sort.by(Direction.DESC, "createdAt"))
     * → page 0, size 20, sorted by created_at descending
     * This is Spring Data's pagination API — no raw SQL needed.
     */
    public Flux<RcaSession> getRecentSessions() {
        return Mono.fromCallable(() ->
                sessionRepository.findAll(
                        PageRequest.of(0, 20,
                                Sort.by(Sort.Direction.DESC, "createdAt"))
                ).getContent()
        )
        //Run that blocking DB call on worker threads made for blocking tasks.
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(Flux::fromIterable);
    }

    /*
     * Returns a single session by ID.
     * Used when the frontend needs full RCA output for display.
     */
    public Mono<RcaSession> getSession(UUID id) {
        return Mono.fromCallable(() ->
                sessionRepository.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Session not found: " + id))
        ).subscribeOn(Schedulers.boundedElastic());
    }
}
