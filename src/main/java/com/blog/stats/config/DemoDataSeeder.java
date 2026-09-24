package com.blog.stats.config;

import com.blog.stats.entity.Article;
import com.blog.stats.entity.ArticleEvent;
import com.blog.stats.entity.EventType;
import com.blog.stats.repository.ArticleEventRepository;
import com.blog.stats.repository.ArticleRepository;
import com.blog.stats.service.AggregationJob;
import com.blog.stats.util.TimeZones;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Profil "demo" : si la table article est vide, crée 8 articles et ~60 jours d'événements réalistes
 * (générateur déterministe, graine 42), puis calcule les agrégats jusqu'à hier.
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder {

    static final int DAYS = 60;
    static final long SEED = 42L;
    static final long DELETED_ARTICLE_ID = 8L;
    /** L'article supprimé ne reçoit plus d'événements depuis sa suppression. */
    static final int DELETED_DAYS_AGO = 12;

    private static final String[] TITLES = {
        "Débuter avec Spring Boot",
        "Symfony vs Spring : le match des frameworks",
        "Java 21 : les virtual threads expliqués",
        "Docker Compose pour les développeurs PHP",
        "Sécuriser une API REST avec Spring Security",
        "Doctrine ou JPA : deux ORM, une philosophie",
        "Tester son code avec JUnit 5 et Mockito",
        "Migrer de Symfony 6 à Symfony 8",
    };
    /** Popularité relative (probabilité d'être ouvert) de chaque article. */
    private static final double[] POPULARITY = {30, 22, 14, 10, 7, 4, 2, 6};
    /** Propension à être lu jusqu'au bout. */
    private static final double[] ENGAGEMENT = {0.60, 0.45, 0.55, 0.40, 0.50, 0.35, 0.30, 0.40};

    private final ArticleRepository articleRepository;
    private final ArticleEventRepository eventRepository;
    private final AggregationJob aggregationJob;
    private final Clock clock;
    private final CacheManager cacheManager;
    private final PlatformTransactionManager transactionManager;

    /** Après le rattrapage d'agrégation (qui n'a rien à faire sur une base vide). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(AggregationJob.CATCH_UP_ORDER + 10)
    public void seed() {
        if (articleRepository.count() > 0) {
            log.info("Demo data skipped: article table is not empty");
            return;
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, TimeZones.PARIS);
        Random random = new Random(SEED);

        List<ArticleEvent> events = buildEvents(today, now, random);
        // Tout ou rien : un échec ne doit pas laisser des articles sans événements (le seeder se croirait déjà passé)
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            articleRepository.saveAll(buildArticles(today, now));
            eventRepository.saveAll(events);
        });

        LocalDate firstDay = today.minusDays(DAYS - 1L);
        aggregationJob.backfill(firstDay, today.minusDays(1));
        // Une requête arrivée pendant le chargement ne doit pas garder des chiffres partiels en cache
        cacheManager.getCacheNames().forEach(name -> Optional.ofNullable(cacheManager.getCache(name))
                .ifPresent(Cache::clear));
        log.info("Demo data: {} articles, {} events from {} to {}, aggregates up to {}",
                TITLES.length, events.size(), firstDay, today, today.minusDays(1));
    }

    private List<Article> buildArticles(LocalDate today, Instant now) {
        List<Article> articles = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) {
            long id = i + 1L;
            Instant createdAt = today.minusDays(DAYS + 30L - i * 3L)
                    .atTime(LocalTime.of(8, 0)).atZone(TimeZones.PARIS).toInstant();
            articles.add(new Article(id, TITLES[i], createdAt, id == DELETED_ARTICLE_ID, now));
        }
        return articles;
    }

    private List<ArticleEvent> buildEvents(LocalDate today, Instant now, Random random) {
        List<ArticleEvent> events = new ArrayList<>();
        for (int daysAgo = DAYS - 1; daysAgo >= 0; daysAgo--) {
            LocalDate day = today.minusDays(daysAgo);
            Instant dayStart = day.atStartOfDay(TimeZones.PARIS).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(TimeZones.PARIS).toInstant();
            if (dayEnd.isAfter(now)) {
                dayEnd = now;
            }
            long daySeconds = dayEnd.getEpochSecond() - dayStart.getEpochSecond();
            if (daySeconds <= 0) {
                continue;
            }
            // Trafic en légère croissance, creux le week-end, part de jour entamé pour aujourd'hui
            int weekday = day.getDayOfWeek().getValue();
            double base = 25 + (DAYS - daysAgo) / 3.0 + random.nextInt(15);
            if (weekday >= 6) {
                base *= 0.6;
            }
            int sessions = (int) Math.round(base * daySeconds / 86_400.0);
            for (int s = 0; s < sessions; s++) {
                Instant start = dayStart.plusSeconds((long) (random.nextDouble() * daySeconds));
                addSession(events, start, now, daysAgo, random);
            }
        }
        return events;
    }

    /**
     * Une visite : 1 à 3 articles distincts, chacun vu une fois ; parfois un retour sur le premier
     * article plus de 30 min après (cohérent avec le dédoublonnage des vues).
     */
    private void addSession(List<ArticleEvent> events, Instant start, Instant now, int daysAgo, Random random) {
        UUID sessionId = randomUuidV4(random);
        int articleCount = 1 + (random.nextDouble() < 0.3 ? 1 : 0) + (random.nextDouble() < 0.1 ? 1 : 0);
        Set<Integer> seen = new HashSet<>();
        Integer first = null;
        Instant t = start;
        for (int n = 0; n < articleCount && t.isBefore(now); n++) {
            int index = pickArticle(random, daysAgo);
            if (!seen.add(index)) {
                continue;
            }
            if (first == null) {
                first = index;
            }
            t = addVisit(events, sessionId, index, t, now, random);
        }
        if (first != null && random.nextDouble() < 0.12) {
            Instant back = t.plusSeconds(1800 + random.nextInt(4 * 3600));
            if (back.isBefore(now)) {
                addVisit(events, sessionId, first, back, now, random);
            }
        }
    }

    /** Un VIEW puis, le plus souvent, un READ ; renvoie l'instant de la visite suivante. */
    private Instant addVisit(List<ArticleEvent> events, UUID sessionId, int index, Instant t, Instant now,
                             Random random) {
        long articleId = index + 1L;
        events.add(ArticleEvent.builder().articleId(articleId).sessionId(sessionId)
                .type(EventType.VIEW).occurredAt(t).build());

        int timeSpent;
        int scroll;
        double kind = random.nextDouble();
        if (kind < 0.15) {                                   // rebond (< 2 s, écarté des moyennes)
            timeSpent = random.nextInt(2);
            scroll = random.nextInt(10);
        } else if (kind < 1 - ENGAGEMENT[index]) {           // survol
            timeSpent = 3 + random.nextInt(60);
            scroll = 10 + random.nextInt(70);
        } else {                                             // lecture attentive
            timeSpent = 45 + random.nextInt(600);
            scroll = 80 + random.nextInt(21);
        }
        Instant readAt = t.plusSeconds(timeSpent);
        // ~80 % des lectures envoient un READ (sendBeacon perdu sinon)
        if (random.nextDouble() < 0.8 && !readAt.isAfter(now)) {
            events.add(ArticleEvent.builder().articleId(articleId).sessionId(sessionId)
                    .type(EventType.READ).timeSpentSeconds(timeSpent).scrollPercent(scroll)
                    .occurredAt(readAt).build());
        }
        return readAt.plusSeconds(5 + random.nextInt(60));
    }

    private static int pickArticle(Random random, int daysAgo) {
        double total = 0;
        for (int i = 0; i < POPULARITY.length; i++) {
            total += weight(i, daysAgo);
        }
        double r = random.nextDouble() * total;
        for (int i = 0; i < POPULARITY.length; i++) {
            r -= weight(i, daysAgo);
            if (r < 0) {
                return i;
            }
        }
        return 0;
    }

    private static double weight(int index, int daysAgo) {
        boolean deleted = index + 1L == DELETED_ARTICLE_ID;
        return deleted && daysAgo < DELETED_DAYS_AGO ? 0 : POPULARITY[index];
    }

    /** UUID v4 reproductible (variante RFC 9562) : MariaDB rejette certains UUID aux bits quelconques. */
    static UUID randomUuidV4(Random random) {
        long msb = (random.nextLong() & ~0xF000L) | 0x4000L;
        long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
