package com.opsbot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/*
 * DTO for incoming feedback requests.
 *
 * Why a separate DTO and not directly using RcaFeedback entity?
 * The entity has a full RcaSession object as a field. If we accepted
 * the entity directly, the caller would have to send the entire session
 * object in the request body. A DTO lets the caller send just the
 * sessionId (UUID) and we look up the full session in the service layer.
 *
 * This is the standard DTO pattern — the API contract (DTO) is
 * decoupled from the storage model (entity).
 *
 * MATURE ALTERNATIVES for DTO:
 * - Java record — immutable, less boilerplate (Java 16+)
 *   record FeedbackRequest(UUID sessionId, Short rating, String comment) {}
 * - Lombok @Value — immutable class with all args constructor
 * - MapStruct — for complex DTO <-> entity mapping
 * We use a plain class here for clarity. Java record is the modern choice.
 */
public class FeedbackRequest {

    @NotNull(message = "sessionId is required")
    private UUID sessionId;

    @NotNull(message = "rating is required")
    @Min(value = 1, message = "rating must be at least 1")
    @Max(value = 5, message = "rating must be at most 5")
    private Short rating;

    private String comment;  // optional

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public Short getRating() { return rating; }
    public void setRating(Short rating) { this.rating = rating; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
