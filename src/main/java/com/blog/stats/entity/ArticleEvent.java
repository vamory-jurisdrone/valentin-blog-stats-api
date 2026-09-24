package com.blog.stats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Événement brut (source de vérité). Horodatage UTC fixé par le serveur. */
@Entity
@Table(name = "article_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EventType type;

    /** READ uniquement. */
    @Column(name = "time_spent_seconds")
    private Integer timeSpentSeconds;

    /** READ uniquement, 0–100. */
    @Column(name = "scroll_percent")
    private Integer scrollPercent;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
