package com.blog.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.blog.stats.dto.ArticleStatsResponse;
import com.blog.stats.dto.TopResponse;
import com.blog.stats.dto.TrendsResponse;
import com.blog.stats.entity.Article;
import com.blog.stats.entity.ArticleEvent;
import com.blog.stats.entity.DailyArticleStats;
import com.blog.stats.entity.DailyArticleStatsId;
import com.blog.stats.entity.EventType;
import com.blog.stats.exception.ArticleNotFoundException;
import com.blog.stats.repository.ArticleEventRepository;
import com.blog.stats.repository.ArticleRepository;
import com.blog.stats.repository.DailyArticleStatsRepository;
import com.blog.stats.support.MutableClock;
import com.blog.stats.util.Period;
import com.blog.stats.util.TimeZones;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Service + job sur H2 (mode MariaDB) avec une horloge fixe : aujourd'hui = 2026-09-24 (Paris).
 * Base dédiée pour ne pas partager "stats" avec les autres contextes de test.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:stats-it;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=DAY")
@ActiveProfiles("local")
class StatsIntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");   // 12:00 à Paris
    static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private StatsService statsService;
    @Autowired
    private AggregationJob aggregationJob;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleEventRepository eventRepository;
    @Autowired
    private DailyArticleStatsRepository dailyRepository;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private MutableClock clock;

    private final List<ArticleEvent> pending = new ArrayList<>();

    @BeforeEach
    void reset() {
        dailyRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        articleRepository.deleteAllInBatch();
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
        clock.set(NOW);
        pending.clear();
    }

    // --- J2 : 10 événements connus ---

    @Test
    void j2_fourMetricsAreExactOnTenKnownEvents() {
        article(42L, "Débuter avec Spring Boot", false);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        view(42L, a, "2026-09-24T06:00:00Z");
        view(42L, b, "2026-09-24T06:05:00Z");
        view(42L, c, "2026-09-24T06:10:00Z");
        view(42L, a, "2026-09-24T07:00:00Z");              // même session, > 30 min plus tard
        view(42L, d, "2026-09-24T07:10:00Z");
        read(42L, a, 120, 95, "2026-09-24T06:02:00Z");     // complète
        read(42L, b, 1, 5, "2026-09-24T06:05:01Z");        // < 2 s : écartée de la moyenne
        read(42L, c, 20, 95, "2026-09-24T06:10:20Z");      // scroll 95 mais 20 s : PAS complète
        read(42L, a, 45, 90, "2026-09-24T07:00:45Z");      // complète (bornes 90 % et 30 s)
        read(42L, d, 300, 60, "2026-09-24T07:15:00Z");     // retenue, pas complète
        flush();

        ArticleStatsResponse stats = statsService.getArticleStats(42L, Period.ALL);

        assertThat(stats.views()).isEqualTo(5);
        assertThat(stats.uniqueReaders()).isEqualTo(4);
        assertThat(stats.avgReadTimeSeconds()).isEqualTo(121);      // (120 + 20 + 45 + 300) / 4 = 121.25
        assertThat(stats.completionRate()).isEqualTo(0.40);         // 2 complètes / 5 vues
        assertThat(stats.lastViewedAt()).isEqualTo(Instant.parse("2026-09-24T07:10:00Z"));
        assertThat(stats.title()).isEqualTo("Débuter avec Spring Boot");
        assertThat(stats.period()).isEqualTo("all");
        assertThat(statsService.getArticleStats(42L, Period.H24)).isEqualTo(
                new ArticleStatsResponse(42L, "Débuter avec Spring Boot", "24h", 5, 4, 121, 0.40,
                        Instant.parse("2026-09-24T07:10:00Z")));
    }

    @Test
    void articleStatsHonourPeriodAndIgnoreOtherArticles() {
        article(1L, "Un", false);
        view(1L, UUID.randomUUID(), "2026-09-24T09:00:00Z");
        view(1L, UUID.randomUUID(), "2026-09-14T09:00:00Z");          // 10 jours avant
        view(1L, UUID.randomUUID(), "2026-09-24T10:30:00Z");          // dans le futur : ignoré
        view(2L, UUID.randomUUID(), "2026-09-24T09:00:00Z");
        flush();

        assertThat(statsService.getArticleStats(1L, Period.D7).views()).isEqualTo(1);
        assertThat(statsService.getArticleStats(1L, Period.D30).views()).isEqualTo(2);
        assertThat(statsService.getArticleStats(1L, Period.ALL).views()).isEqualTo(2);
    }

    @Test
    void articleStatsReturns404ForUnknownAndZerosForDeletedArticle() {
        article(5L, "Supprimé", true);

        assertThatThrownBy(() -> statsService.getArticleStats(404L, Period.ALL))
                .isInstanceOf(ArticleNotFoundException.class);
        assertThat(statsService.getArticleStats(5L, Period.D7))
                .isEqualTo(new ArticleStatsResponse(5L, "Supprimé", "7d", 0, 0, 0, 0.0, null));
    }

    // --- Trends ---

    @Test
    void trendsOnThirtyDaysReturnsThirtyPointsIncludingZerosAndToday() {
        article(1L, "Un", false);
        article(2L, "Deux", true);
        viewsOn(1L, LocalDate.of(2026, 8, 26), 3);   // premier jour de la fenêtre
        viewsOn(2L, LocalDate.of(2026, 9, 10), 2);   // article supprimé : compte pour tout le blog
        viewsOn(1L, LocalDate.of(2026, 8, 25), 7);   // hors fenêtre
        viewsOn(1L, TODAY, 4);                       // aujourd'hui, pas encore agrégé
        flush();
        aggregationJob.backfill(LocalDate.of(2026, 8, 20), TODAY.minusDays(1));

        TrendsResponse trends = statsService.getTrends(TODAY.minusDays(29), TODAY, null, TODAY);

        assertThat(trends.points()).hasSize(30);
        assertThat(trends.points().get(0)).isEqualTo(point("2026-08-26", 3, 3));
        assertThat(trends.points().get(1)).isEqualTo(point("2026-08-27", 0, 0));
        assertThat(trends.points()).contains(point("2026-09-10", 2, 2));
        assertThat(trends.points().get(29)).isEqualTo(point("2026-09-24", 4, 4));
        assertThat(trends.totalViews()).isEqualTo(9);
        assertThat(trends.articleId()).isNull();

        TrendsResponse one = statsService.getTrends(TODAY.minusDays(29), TODAY, 1L, TODAY);
        assertThat(one.totalViews()).isEqualTo(7);
        assertThat(one.articleId()).isEqualTo(1L);
    }

    @Test
    void trendsFillFutureDaysWithZerosAndRejectUnknownArticle() {
        article(1L, "Un", false);
        viewsOn(1L, TODAY, 2);
        flush();

        TrendsResponse trends = statsService.getTrends(TODAY.minusDays(1), TODAY.plusDays(2), null, TODAY);

        assertThat(trends.points()).containsExactly(
                point("2026-09-23", 0, 0), point("2026-09-24", 2, 2),
                point("2026-09-25", 0, 0), point("2026-09-26", 0, 0));
        assertThat(statsService.getTrends(TODAY.minusDays(5), TODAY.minusDays(3), null, TODAY).totalViews())
                .isZero();
        assertThatThrownBy(() -> statsService.getTrends(TODAY, TODAY, 99L, TODAY))
                .isInstanceOf(ArticleNotFoundException.class);
    }

    // --- Top ---

    @Test
    void topExcludesDeletedAndUnknownArticlesAndMergesToday() {
        article(1L, "Un", false);
        article(2L, "Deux (supprimé)", true);
        article(3L, "Trois", false);
        article(4L, "Quatre", false);
        viewsOn(1L, TODAY.minusDays(3), 6);
        viewsOn(1L, TODAY, 4);                          // 10 vues dont 4 aujourd'hui
        viewsOn(2L, TODAY.minusDays(2), 20);
        viewsOn(99L, TODAY.minusDays(2), 50);           // id inconnu de la table article
        viewsOn(3L, TODAY.minusDays(1), 12);
        viewsOn(4L, TODAY.minusDays(6), 3);
        viewsOn(4L, TODAY.minusDays(7), 30);            // hors des 7 jours
        UUID reader = UUID.randomUUID();
        view(1L, reader, "2026-09-24T09:00:00Z");       // 11e vue, lecteur qui finit l'article
        read(1L, reader, 200, 100, "2026-09-24T09:03:20Z");
        flush();
        aggregationJob.backfill(TODAY.minusDays(10), TODAY.minusDays(1));

        TopResponse top = statsService.getTop(Period.D7, 5, TODAY);

        assertThat(top.generatedAt()).isEqualTo(NOW);
        assertThat(top.items()).extracting(TopResponse.Item::articleId).containsExactly(3L, 1L, 4L);
        assertThat(top.items().get(0)).isEqualTo(new TopResponse.Item(1, 3L, "Trois", 12, 12, 0.0));
        assertThat(top.items().get(1)).isEqualTo(new TopResponse.Item(2, 1L, "Un", 11, 11, 0.09));
        assertThat(statsService.getTop(Period.D7, 2, TODAY).items()).hasSize(2);
        assertThat(statsService.getTop(Period.ALL, 5, TODAY).items())
                .extracting(TopResponse.Item::articleId).containsExactly(4L, 3L, 1L);
        // 24h glissantes : seulement depuis hier 12:00 Paris
        assertThat(statsService.getTop(Period.H24, 5, TODAY).items())
                .extracting(TopResponse.Item::articleId, TopResponse.Item::views)
                .containsExactly(tuple(1L, 5L));
    }

    @Test
    void topIsCachedUntilEviction() {
        article(1L, "Un", false);
        viewsOn(1L, TODAY, 2);
        flush();
        assertThat(statsService.getTop(Period.D7, 5, TODAY).items().get(0).views()).isEqualTo(2);

        viewsOn(1L, TODAY, 3);
        flush();
        assertThat(statsService.getTop(Period.D7, 5, TODAY).items().get(0).views()).isEqualTo(2);

        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
        assertThat(statsService.getTop(Period.D7, 5, TODAY).items().get(0).views()).isEqualTo(5);
    }

    // --- Agrégation ---

    @Test
    void aggregateDayIsIdempotentAndComputesAllColumns() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 9, 20);
        view(1L, a, "2026-09-20T08:00:00Z");
        view(1L, b, "2026-09-20T08:00:00Z");
        view(1L, a, "2026-09-20T12:00:00Z");
        read(1L, a, 100, 95, "2026-09-20T08:01:40Z");
        read(1L, b, 1, 100, "2026-09-20T08:00:01Z");       // écartée (< 2 s)
        read(1L, b, 20, 100, "2026-09-20T12:00:20Z");      // retenue, non complète
        read(2L, b, 40, 10, "2026-09-20T13:00:00Z");       // READ sans VIEW
        flush();

        aggregationJob.aggregateDay(day);
        aggregationJob.aggregateDay(day);

        assertThat(dailyRepository.findAll()).hasSize(2);
        DailyArticleStats row = dailyRepository.findById(new DailyArticleStatsId(1L, day)).orElseThrow();
        assertThat(row.getViews()).isEqualTo(3);
        assertThat(row.getUniqueReaders()).isEqualTo(2);
        assertThat(row.getReadCount()).isEqualTo(2);
        assertThat(row.getTotalReadTimeSeconds()).isEqualTo(120);
        assertThat(row.getCompletedReads()).isEqualTo(1);
        DailyArticleStats readOnly = dailyRepository.findById(new DailyArticleStatsId(2L, day)).orElseThrow();
        assertThat(readOnly.getViews()).isZero();
        assertThat(readOnly.getReadCount()).isEqualTo(1);

        // Un nouvel événement puis un recalcul remplace la ligne
        view(1L, UUID.randomUUID(), "2026-09-20T20:00:00Z");
        flush();
        aggregationJob.aggregateDay(day);
        assertThat(dailyRepository.findById(new DailyArticleStatsId(1L, day)).orElseThrow().getViews())
                .isEqualTo(4);
        assertThat(dailyRepository.count()).isEqualTo(2);
    }

    @Test
    void dstDayBoundariesFollowParisCalendar() {
        // 25/10/2026 : passage à l'heure d'hiver, journée de 25 h (22:00Z le 24 -> 23:00Z le 25)
        view(1L, UUID.randomUUID(), "2026-10-24T21:59:59Z");   // 23:59:59 CEST le 24
        view(1L, UUID.randomUUID(), "2026-10-24T23:30:00Z");   // 01:30 CEST le 25
        view(1L, UUID.randomUUID(), "2026-10-25T22:30:00Z");   // 23:30 CET le 25
        view(1L, UUID.randomUUID(), "2026-10-25T23:00:00Z");   // 00:00 CET le 26
        flush();

        aggregationJob.backfill(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 26));

        assertThat(viewsOf(1L, LocalDate.of(2026, 10, 24))).isEqualTo(1);
        assertThat(viewsOf(1L, LocalDate.of(2026, 10, 25))).isEqualTo(2);
        assertThat(viewsOf(1L, LocalDate.of(2026, 10, 26))).isEqualTo(1);
    }

    @Test
    void catchUpAggregatesMissingPastDaysOnly() {
        viewsOn(1L, LocalDate.of(2026, 9, 20), 2);
        viewsOn(1L, LocalDate.of(2026, 9, 22), 1);
        viewsOn(1L, TODAY, 5);
        flush();

        aggregationJob.catchUp();

        assertThat(dailyRepository.findMaxDay()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(viewsOf(1L, LocalDate.of(2026, 9, 20))).isEqualTo(2);
        assertThat(dailyRepository.findAll()).noneMatch(r -> r.getDay().equals(TODAY));

        // Le lendemain : jours après le dernier agrégé + les 3 derniers jours recalculés, pas au-delà
        clock.set(Instant.parse("2026-09-26T10:00:00Z"));
        viewsOn(1L, LocalDate.of(2026, 9, 20), 1);   // arrivé tard, hors fenêtre : pas recalculé
        flush();
        aggregationJob.catchUp();
        assertThat(viewsOf(1L, LocalDate.of(2026, 9, 20))).isEqualTo(2);
        assertThat(viewsOf(1L, TODAY)).isEqualTo(5);
        assertThat(dailyRepository.findMaxDay()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void catchUpIsBoundedByRetentionAndNoopWithoutEvents() {
        aggregationJob.catchUp();
        assertThat(dailyRepository.count()).isZero();

        viewsOn(1L, LocalDate.of(2024, 1, 10), 1);      // bien avant la fenêtre de 13 mois
        viewsOn(1L, TODAY.minusDays(2), 1);
        flush();
        aggregationJob.catchUp();

        LocalDate earliest = dailyRepository.findAll().stream()
                .map(DailyArticleStats::getDay).min(Comparator.naturalOrder()).orElseThrow();
        assertThat(earliest).isEqualTo(TODAY.minusDays(2));
        assertThat(dailyRepository.findMaxDay()).isEqualTo(TODAY.minusDays(2));
    }

    @Test
    void nightlyRunAggregatesYesterdayAndPurgesExpiredEvents() {
        viewsOn(1L, TODAY.minusDays(1), 3);
        viewsOn(1L, TODAY.minusMonths(13).minusDays(1), 1);   // expiré
        viewsOn(1L, TODAY.minusMonths(12), 1);                 // conservé
        flush();

        aggregationJob.runNightly();

        assertThat(viewsOf(1L, TODAY.minusDays(1))).isEqualTo(3);
        assertThat(eventRepository.count()).isEqualTo(4);
    }

    @Test
    void yesterdayNotYetAggregatedIsServedLive() {
        // 00:02 à Paris : le job de 00:05 n'a pas encore agrégé la veille
        article(1L, "Un", false);
        viewsOn(1L, TODAY.minusDays(2), 2);
        viewsOn(1L, TODAY.minusDays(1), 3);
        viewsOn(1L, TODAY, 1);
        flush();
        aggregationJob.aggregateDay(TODAY.minusDays(2));
        clock.set(TODAY.atTime(0, 2).atZone(TimeZones.PARIS).toInstant().plusSeconds(86_400));
        // l'horloge est maintenant le lendemain de TODAY à 00:02 : TODAY et la veille ne sont pas agrégés
        LocalDate today = TODAY.plusDays(1);

        TopResponse top = statsService.getTop(Period.D7, 5, today);
        TrendsResponse trends = statsService.getTrends(today.minusDays(3), today, null, today);

        assertThat(top.items()).singleElement().extracting(TopResponse.Item::views).isEqualTo(6L);
        assertThat(trends.points()).extracting(TrendsResponse.Point::views).containsExactly(2L, 3L, 1L, 0L);
    }

    @Test
    void nightlyRunRepairsAMissedDayAndClearsCaches() {
        article(1L, "Un", false);
        viewsOn(1L, TODAY.minusDays(3), 1);
        viewsOn(1L, TODAY.minusDays(2), 4);   // passage de nuit raté pour ce jour
        viewsOn(1L, TODAY.minusDays(1), 2);
        flush();
        aggregationJob.aggregateDay(TODAY.minusDays(3));
        aggregationJob.aggregateDay(TODAY.minusDays(1));
        TopResponse before = statsService.getTop(Period.D7, 5, TODAY);   // mis en cache

        aggregationJob.runNightly();

        assertThat(viewsOf(1L, TODAY.minusDays(2))).isEqualTo(4);
        assertThat(before.items().get(0).views()).isEqualTo(3);
        assertThat(statsService.getTop(Period.D7, 5, TODAY).items().get(0).views()).isEqualTo(7);
    }

    @Test
    void backfillRejectsInvertedRange() {
        assertThatThrownBy(() -> aggregationJob.backfill(TODAY, TODAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Outils ---

    private int viewsOf(Long articleId, LocalDate day) {
        return dailyRepository.findById(new DailyArticleStatsId(articleId, day))
                .map(DailyArticleStats::getViews).orElse(0);
    }

    private static TrendsResponse.Point point(String day, long views, long readers) {
        return new TrendsResponse.Point(LocalDate.parse(day), views, readers);
    }

    private void article(Long id, String title, boolean deleted) {
        articleRepository.save(new Article(id, title, NOW.minusSeconds(86_400 * 400L), deleted, NOW));
    }

    /** n vues de sessions distinctes, à 09:00 heure de Paris ce jour-là. */
    private void viewsOn(Long articleId, LocalDate day, int n) {
        Instant at = day.atTime(LocalTime.of(9, 0)).atZone(TimeZones.PARIS).toInstant();
        for (int i = 0; i < n; i++) {
            pending.add(event(articleId, UUID.randomUUID(), EventType.VIEW, null, null, at.plusSeconds(i)));
        }
    }

    private void view(Long articleId, UUID session, String at) {
        pending.add(event(articleId, session, EventType.VIEW, null, null, Instant.parse(at)));
    }

    private void read(Long articleId, UUID session, int seconds, int scroll, String at) {
        pending.add(event(articleId, session, EventType.READ, seconds, scroll, Instant.parse(at)));
    }

    private void flush() {
        eventRepository.saveAll(pending);
        pending.clear();
    }

    private static ArticleEvent event(Long articleId, UUID session, EventType type, Integer seconds,
                                      Integer scroll, Instant at) {
        return ArticleEvent.builder().articleId(articleId).sessionId(session).type(type)
                .timeSpentSeconds(seconds).scrollPercent(scroll).occurredAt(at).build();
    }
}
