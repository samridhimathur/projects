package com.opsbot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "rca_sessions")
@Data
@NoArgsConstructor
public class RcaSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /*
     * @JdbcTypeCode(SqlTypes.JSON) tells Hibernate to persist this as a Postgres
     * JSONB column instead of trying to serialize it as a VARCHAR.
     * Using Map<String,Object> keeps it schema-flexible — alert payloads vary
     * wildly across monitoring tools (PagerDuty, Prometheus, Datadog).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "alert_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> alertPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rca_output", columnDefinition = "jsonb")
    private Map<String, Object> rcaOutput;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status = SessionStatus.PENDING;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public enum SessionStatus {
        PENDING, COMPLETE, REVIEWED
    }
}
