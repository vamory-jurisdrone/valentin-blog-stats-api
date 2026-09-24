package com.blog.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.blog.stats.config.CacheConfig;
import com.blog.stats.dto.ArticleSyncRequest;
import com.blog.stats.dto.ReadEventRequest;
import com.blog.stats.dto.ViewEventRequest;
import com.blog.stats.entity.Article;
import com.blog.stats.entity.ArticleEvent;
import com.blog.stats.entity.EventType;
import com.blog.stats.repository.ArticleEventRepository;
import com.blog.stats.repository.ArticleRepository;
import com.blog.stats.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ingestion (événements et synchro des articles) de bout en bout sur H2 (mode MariaDB), avec de vraies
 * transactions validées : pas de transaction de test englobante, chaque appel commit comme en production.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ingestion-it;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;"
        + "DB_CLOSE_DELAY=-1;NON_KEYWORDS=DAY;LOCK_TIMEOUT=10000")
@ActiveProfiles("local")
class EventIngestionIntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");   // 12:00 à Paris
    static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0 Safari/537.36";
    static final long ARTICLE_ID = 42L;
    static final int THREADS = 16;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private EventService eventService;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleEventRepository eventRepository;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ArticleService articleService;
    @Autowired
    private CacheManager cacheManager;

    private final UUID session = UUID.randomUUID();

    @BeforeEach
    void reset() {
        eventRepository.deleteAllInBatch();
        articleRepository.deleteAllInBatch();
        articleRepository.save(new Article(ARTICLE_ID, "Titre", NOW.minus(Duration.ofDays(30)), false, NOW));
        clock.set(NOW);
    }

    // --- Concurrence : 0 vue en double ---

    /** Rafale de vues simultanées pour le même couple (session, article) : une seule ligne VIEW. */
    @RepeatedTest(5)
    void concurrentViewsOfSameSessionAreStoredOnce() throws Exception {
        runConcurrently(() -> eventService.recordView(new ViewEventRequest(ARTICLE_ID, session), BROWSER));

        assertThat(rows(EventType.VIEW)).hasSize(1);
    }

    /** Idem pour les READ : les envois simultanés fusionnent dans une seule ligne, maxima conservés. */
    @RepeatedTest(3)
    void concurrentReadsOfSameSessionAreMergedIntoOneRow() throws Exception {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 1; i <= THREADS; i++) {
            int seconds = i * 10;
            tasks.add(() -> {
                eventService.recordRead(new ReadEventRequest(ARTICLE_ID, session, seconds, 50), BROWSER);
                return null;
            });
        }
        runConcurrently(tasks);

        assertThat(rows(EventType.READ))
                .extracting(ArticleEvent::getTimeSpentSeconds)
                .containsExactly(THREADS * 10);
    }

    @Test
    void concurrentViewsOfDifferentSessionsAreAllStored() throws Exception {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                eventService.recordView(new ViewEventRequest(ARTICLE_ID, UUID.randomUUID()), BROWSER);
                return null;
            });
        }
        runConcurrently(tasks);

        assertThat(eventRepository.count()).isEqualTo(THREADS);
    }

    // --- Fusion des READ ---

    @Test
    void intermediateReadsOfTheSamePageViewShareOneRow() {
        eventService.recordView(new ViewEventRequest(ARTICLE_ID, session), BROWSER);
        read(NOW.plusSeconds(30), 30, 20);
        read(NOW.plusSeconds(60), 60, 55);
        read(NOW.plusSeconds(75), 70, 40);        // sendBeacon final : scroll plus faible, on garde 55

        assertThat(rows(EventType.READ))
                .extracting(ArticleEvent::getTimeSpentSeconds, ArticleEvent::getScrollPercent,
                        ArticleEvent::getOccurredAt)
                .containsExactly(tuple(70, 55, NOW.plusSeconds(75)));
    }

    /** 23:50 puis 00:10 (Paris) : deux lignes, sinon la lecture compterait dans deux agrégats journaliers. */
    @Test
    void readCrossingParisMidnightStartsANewRow() {
        Instant beforeMidnight = Instant.parse("2026-09-24T21:50:00Z");   // 23:50 à Paris
        Instant afterMidnight = Instant.parse("2026-09-24T22:10:00Z");    // 00:10 le 25 à Paris
        read(beforeMidnight, 60, 40);
        read(afterMidnight, 1260, 90);

        assertThat(rows(EventType.READ))
                .extracting(ArticleEvent::getOccurredAt, ArticleEvent::getTimeSpentSeconds,
                        ArticleEvent::getScrollPercent)
                .containsExactly(tuple(beforeMidnight, 60, 40), tuple(afterMidnight, 1260, 90));
    }

    /**
     * Deux affichages de la page, chacun avec une lecture partielle : 1200 s à 40 %, puis 10 s à 95 %.
     * Fusionnées, elles inventeraient une lecture complète (≥ 30 s et ≥ 90 %).
     */
    @Test
    void readAfterANewStoredViewStartsANewRow() {
        Instant t0 = NOW;
        eventService.recordView(new ViewEventRequest(ARTICLE_ID, session), BROWSER);
        read(t0.plus(Duration.ofMinutes(20)), 1200, 40);
        clock.set(t0.plus(Duration.ofMinutes(35)));
        eventService.recordView(new ViewEventRequest(ARTICLE_ID, session), BROWSER);   // > 30 min : enregistrée
        read(t0.plus(Duration.ofMinutes(35)).plusSeconds(10), 10, 95);

        assertThat(rows(EventType.VIEW)).hasSize(2);
        assertThat(rows(EventType.READ))
                .extracting(ArticleEvent::getOccurredAt, ArticleEvent::getTimeSpentSeconds,
                        ArticleEvent::getScrollPercent)
                .containsExactly(
                        tuple(t0.plus(Duration.ofMinutes(20)), 1200, 40),
                        tuple(t0.plus(Duration.ofMinutes(35)).plusSeconds(10), 10, 95));
    }

    /** Vue en doublon (rechargement sous 30 min) : non enregistrée, donc la lecture en cours continue. */
    @Test
    void duplicateViewDoesNotSplitTheRead() {
        eventService.recordView(new ViewEventRequest(ARTICLE_ID, session), BROWSER);
        read(NOW.plus(Duration.ofMinutes(5)), 120, 50);
        clock.set(NOW.plus(Duration.ofMinutes(10)));
        eventService.recordView(new ViewEventRequest(ARTICLE_ID, session), BROWSER);
        read(NOW.plus(Duration.ofMinutes(11)), 30, 95);

        assertThat(rows(EventType.VIEW)).hasSize(1);
        assertThat(rows(EventType.READ))
                .extracting(ArticleEvent::getTimeSpentSeconds, ArticleEvent::getScrollPercent)
                .containsExactly(tuple(120, 95));
    }

    // --- Synchro des articles : caches /top et /trends vidés ---

    @Test
    void upsertEvictsTopAndTrendsCaches() {
        fillCaches();

        articleService.upsert(ARTICLE_ID, new ArticleSyncRequest("Nouveau titre", OffsetDateTime.parse(
                "2026-09-01T08:00:00+02:00")));

        assertCachesEmpty();
    }

    @Test
    void softDeleteEvictsTopAndTrendsCaches() {
        fillCaches();

        articleService.softDelete(ARTICLE_ID);

        assertCachesEmpty();
    }

    private void fillCaches() {
        for (String name : List.of(CacheConfig.TOP_CACHE, CacheConfig.TRENDS_CACHE)) {
            Objects.requireNonNull(cacheManager.getCache(name)).put("some-key", "stale answer");
        }
    }

    private void assertCachesEmpty() {
        for (String name : List.of(CacheConfig.TOP_CACHE, CacheConfig.TRENDS_CACHE)) {
            assertThat(Objects.requireNonNull(cacheManager.getCache(name)).get("some-key")).as(name).isNull();
        }
    }

    private void read(Instant at, int seconds, int scroll) {
        clock.set(at);
        eventService.recordRead(new ReadEventRequest(ARTICLE_ID, session, seconds, scroll), BROWSER);
    }

    private List<ArticleEvent> rows(EventType type) {
        return eventRepository.findAll().stream()
                .filter(e -> e.getType() == type)
                .sorted(Comparator.comparing(ArticleEvent::getOccurredAt))
                .toList();
    }

    private void runConcurrently(Runnable task) throws Exception {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                task.run();
                return null;
            });
        }
        runConcurrently(tasks);
    }

    /** Lance toutes les tâches au même signal ; une exception dans un thread fait échouer le test. */
    private static void runConcurrently(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
