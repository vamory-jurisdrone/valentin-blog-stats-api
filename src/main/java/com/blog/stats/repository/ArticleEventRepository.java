package com.blog.stats.repository;

import com.blog.stats.entity.ArticleEvent;
import com.blog.stats.entity.EventType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleEventRepository extends JpaRepository<ArticleEvent, Long> {

    /** Dédoublonnage : un événement de ce type existe-t-il déjà pour ce couple (session, article) depuis {@code since} ? */
    boolean existsByTypeAndSessionIdAndArticleIdAndOccurredAtAfter(
            EventType type, UUID sessionId, Long articleId, Instant since);

    /** Dernier événement de ce type pour ce couple (session, article) à partir de {@code since} inclus. */
    Optional<ArticleEvent> findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
            EventType type, UUID sessionId, Long articleId, Instant since);
}
