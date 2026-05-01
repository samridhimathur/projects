package com.opsbot.controller;

import com.opsbot.dto.FeedbackRequest;
import com.opsbot.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /*
     * POST /api/feedback
     *
     * Accepts: { "sessionId": "uuid", "rating": 4, "comment": "Good analysis" }
     * Returns: { "id": 1, "sessionId": "uuid", "rating": 4, "message": "Feedback saved" }
     *
     * @Valid triggers Bean Validation on FeedbackRequest —
     * @NotNull on sessionId and rating, @Min/@Max on rating.
     * If validation fails Spring returns 400 automatically.
     *
     * Why return Mono<ResponseEntity> and not just ResponseEntity?
     * FeedbackService returns Mono<RcaFeedback>. We need to stay
     * in the reactive pipeline to avoid blocking. .map() transforms
     * the result into a ResponseEntity once the Mono completes.
     */
    @PostMapping("/feedback")
    public Mono<ResponseEntity<Map<String, Object>>> submitFeedback(
            @Valid @RequestBody FeedbackRequest request) {

        return feedbackService
                .submitFeedback(
                        request.getSessionId(),
                        request.getRating(),
                        request.getComment())
                .map(feedback -> ResponseEntity.ok(Map.of(
                        "id",        feedback.getId(),
                        "sessionId", request.getSessionId(),
                        "rating",    feedback.getRating(),
                        "message",   "Feedback saved successfully"
                )));
    }

    /*
     * Handle session not found — 404
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(404)
                .body(Map.of("error", ex.getMessage()));
    }

    /*
     * Handle business rule violations — 400
     * e.g. submitting feedback for a PENDING session
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleBadState(IllegalStateException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getMessage()));
    }
}
