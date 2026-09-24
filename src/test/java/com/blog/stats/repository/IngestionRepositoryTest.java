package com.blog.stats.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.stats.entity.Article;
import com.blog.stats.entity.ArticleEvent;
import com.blog.stats.entity.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

/** Requêtes de l'ingestion sur H2 en mode MariaDB (pas de Docker requis). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ingestion;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=DAY",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class IngestionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Instant WINDOW_START = NOW.minus(Duration.ofMinutes(30));
    private static final UUID SESSION = UUID.fromString("3f1c2a9e-7b1d-4c55-9a51-2f0e8f3b6d10");
    private static final UUID OTHER_SESSION = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private ArticleEventRepository eventRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Test
    void viewWithin29MinutesIsADuplicate() {
        save(EventType.VIEW, SESSION, 42L, NOW.minus(Duration.ofMinutes(29)), null, null);

        assertThat(eventRepository.existsByTypeAndSessionIdAndArticleIdAndOccurredAtAfter(
                EventType.VIEW, SESSION, 42L, WINDOW_START)).isTrue();
    }

    @Test
    void viewOlderThan30MinutesIsNotADuplicate() {
        save(EventType.VIEW, SESSION, 42L, NOW.minus(Duration.ofMinutes(31)), null, null);
        save(EventType.VIEW, SESSION, 42L, WINDOW_START, null, null);

        assertThat(eventRepository.existsByTypeAndSessionIdAndArticleIdAndOccurredAtAfter(
                EventType.VIEW, SESSION, 42L, WINDOW_START)).isFalse();
    }

    @Test
    void dedupIsScopedToTypeSessionAndArticle() {
        Instant recent = NOW.minus(Duration.ofMinutes(5));
        save(EventType.READ, SESSION, 42L, recent, 60, 50);
        save(EventType.VIEW, OTHER_SESSION, 42L, recent, null, null);
        save(EventType.VIEW, SESSION, 43L, recent, null, null);

        assertThat(eventRepository.existsByTypeAndSessionIdAndArticleIdAndOccurredAtAfter(
                EventType.VIEW, SESSION, 42L, WINDOW_START)).isFalse();
    }

    @Test
    void findsMostRecentReadInWindow() {
        save(EventType.READ, SESSION, 42L, NOW.minus(Duration.ofMinutes(40)), 300, 100);
        save(EventType.READ, SESSION, 42L, NOW.minus(Duration.ofMinutes(20)), 30, 20);
        ArticleEvent latest = save(EventType.READ, SESSION, 42L, NOW.minus(Duration.ofMinutes(2)), 60, 40);
        save(EventType.VIEW, SESSION, 42L, NOW.minus(Duration.ofMinutes(1)), null, null);

        assertThat(eventRepository.findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                EventType.READ, SESSION, 42L, WINDOW_START))
                .get()
                .extracting(ArticleEvent::getId, ArticleEvent::getTimeSpentSeconds)
                .containsExactly(latest.getId(), 60);
    }

    @Test
    void noReadInWindowReturnsEmpty() {
        save(EventType.READ, SESSION, 42L, NOW.minus(Duration.ofMinutes(31)), 300, 100);

        assertThat(eventRepository.findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                EventType.READ, SESSION, 42L, WINDOW_START)).isEmpty();
    }

    /** Borne incluse : une lecture pile sur la dernière vue appartient encore à cette vue. */
    @Test
    void readMergeLowerBoundIsInclusive() {
        ArticleEvent onBound = save(EventType.READ, SESSION, 42L, WINDOW_START, 30, 20);

        assertThat(eventRepository.findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                EventType.READ, SESSION, 42L, WINDOW_START))
                .get()
                .extracting(ArticleEvent::getId)
                .isEqualTo(onBound.getId());
    }

    @Test
    void findByIdForUpdateReturnsArticleWhateverItsDeletedFlag() {
        articleRepository.save(new Article(1L, "Actif", NOW, false, NOW));
        articleRepository.save(new Article(2L, "Supprimé", NOW, true, NOW));
        articleRepository.flush();

        assertThat(articleRepository.findByIdForUpdate(1L)).get().extracting(Article::isDeleted).isEqualTo(false);
        assertThat(articleRepository.findByIdForUpdate(2L)).get().extracting(Article::isDeleted).isEqualTo(true);
        assertThat(articleRepository.findByIdForUpdate(3L)).isEmpty();
    }

    private ArticleEvent save(EventType type, UUID session, long articleId, Instant at,
                              Integer timeSpent, Integer scroll) {
        return eventRepository.saveAndFlush(ArticleEvent.builder()
                .type(type).sessionId(session).articleId(articleId).occurredAt(at)
                .timeSpentSeconds(timeSpent).scrollPercent(scroll)
                .build());
    }
}
