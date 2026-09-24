package com.blog.stats.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.stats.entity.Article;
import com.blog.stats.entity.ArticleEvent;
import com.blog.stats.entity.EventType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Vraie MariaDB 11.4 : Flyway applique V1__init.sql et les entités s'y lisent/écrivent
 * (UUID natif, utf8mb4). Ignoré proprement quand Docker est absent.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class MariaDbMigrationTest {

    @Container
    @ServiceConnection
    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci",
                    "--default-time-zone=+00:00");

    @Autowired
    TestEntityManager em;

    @Autowired
    ArticleRepository articles;

    @Autowired
    ArticleEventRepository events;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedV1() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void articleWithAccentsAndEmojiRoundTrips() {
        String title = "Débuter avec Spring Boot 🚀 — ça marche !";
        Instant createdAt = Instant.parse("2026-09-01T06:00:00.123456Z");
        articles.save(new Article(42L, title, createdAt, false, createdAt));
        em.flush();
        em.clear();

        Article found = articles.findById(42L).orElseThrow();
        assertThat(found.getTitle()).isEqualTo(title);
        assertThat(found.getCreatedAt()).isEqualTo(createdAt);
        assertThat(found.isDeleted()).isFalse();
    }

    @Test
    void eventWithUuidSessionRoundTrips() {
        UUID session = UUID.fromString("3f1c2a9e-7b1d-4c55-9a51-2f0e8f3b6d10");
        Instant occurredAt = Instant.parse("2026-09-24T09:15:30.5Z");
        ArticleEvent saved = events.save(ArticleEvent.builder()
                .articleId(42L)
                .sessionId(session)
                .type(EventType.READ)
                .timeSpentSeconds(185)
                .scrollPercent(94)
                .occurredAt(occurredAt)
                .build());
        em.flush();
        em.clear();

        ArticleEvent found = events.findById(saved.getId()).orElseThrow();
        assertThat(found.getSessionId()).isEqualTo(session);
        assertThat(found.getType()).isEqualTo(EventType.READ);
        assertThat(found.getTimeSpentSeconds()).isEqualTo(185);
        assertThat(found.getScrollPercent()).isEqualTo(94);
        assertThat(found.getOccurredAt()).isEqualTo(occurredAt);

        // Stocké en UTC dans la colonne DATETIME(6)
        String raw = jdbc.queryForObject(
                "SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d %H:%i:%s') FROM article_event WHERE id = ?",
                String.class, saved.getId());
        assertThat(raw).isEqualTo("2026-09-24 09:15:30");
    }
}
