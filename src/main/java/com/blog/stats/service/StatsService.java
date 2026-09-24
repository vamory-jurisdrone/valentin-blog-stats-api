package com.blog.stats.service;

import com.blog.stats.config.CacheConfig;
import com.blog.stats.dto.ArticleStatsResponse;
import com.blog.stats.dto.TopResponse;
import com.blog.stats.dto.TrendsResponse;
import com.blog.stats.entity.Article;
import com.blog.stats.entity.DailyArticleStats;
import com.blog.stats.exception.ArticleNotFoundException;
import com.blog.stats.repository.ArticleRepository;
import com.blog.stats.repository.DailyArticleStatsRepository;
import com.blog.stats.repository.DailyArticleStatsRepository.ArticleTotals;
import com.blog.stats.repository.DailyArticleStatsRepository.DayTotals;
import com.blog.stats.repository.EventQueryRepository;
import com.blog.stats.repository.EventQueryRepository.ReadAggregate;
import com.blog.stats.repository.EventQueryRepository.ViewAggregate;
import com.blog.stats.util.Period;
import com.blog.stats.util.TimeZones;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calcul des trois vues de stats.
 * <ul>
 *   <li>article : exact, depuis les événements bruts ;</li>
 *   <li>top et trends : agrégats journaliers jusqu'au dernier jour agrégé, puis les jours suivants
 *       (aujourd'hui, et la veille avant le passage de 00:05) calculés à la volée.</li>
 * </ul>
 * Les valeurs par défaut (aujourd'hui, from/to) sont résolues par l'appelant pour que la clé de cache soit concrète.
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    /** Borne basse utilisée pour la période "all". */
    static final LocalDate FIRST_DAY = LocalDate.EPOCH;

    private final ArticleRepository articleRepository;
    private final EventQueryRepository eventQueryRepository;
    private final DailyArticleStatsRepository dailyRepository;
    private final AggregationJob aggregationJob;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ArticleStatsResponse getArticleStats(Long articleId, Period period) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new ArticleNotFoundException(articleId));
        Instant now = clock.instant();
        Instant from = period.startInstant(now);
        if (from == null) {
            from = Instant.EPOCH;
        }
        Instant to = inclusiveEnd(now);

        List<ViewAggregate> viewRows = eventQueryRepository.aggregateViewsOf(articleId, from, to);
        List<ReadAggregate> readRows = eventQueryRepository.aggregateReadsOf(articleId, from, to);
        ViewAggregate views = viewRows.isEmpty() ? null : viewRows.get(0);
        ReadAggregate reads = readRows.isEmpty() ? null : readRows.get(0);

        long viewCount = views == null ? 0 : nz(views.getViews());
        long uniqueReaders = views == null ? 0 : nz(views.getUniqueReaders());
        long readCount = reads == null ? 0 : nz(reads.getReadCount());
        long totalReadTime = reads == null ? 0 : nz(reads.getTotalReadTimeSeconds());
        long completed = reads == null ? 0 : nz(reads.getCompletedReads());

        return new ArticleStatsResponse(
                articleId,
                article.getTitle(),
                period.value(),
                viewCount,
                uniqueReaders,
                averageReadTime(totalReadTime, readCount),
                completionRate(completed, viewCount),
                views == null ? null : views.getLastViewedAt());
    }

    @Cacheable(cacheNames = CacheConfig.TOP_CACHE)
    @Transactional(readOnly = true)
    public TopResponse getTop(Period period, int limit, LocalDate today) {
        Instant now = clock.instant();
        Map<Long, Totals> totals = new HashMap<>();

        if (period.isRolling()) {
            aggregationJob.computeRange(period.startInstant(now), inclusiveEnd(now), null)
                    .forEach(row -> add(totals, row));
        } else {
            LocalDate start = period.startDay(today);
            if (start == null) {
                start = FIRST_DAY;
            }
            LocalDate aggregatedUntil = aggregatedUntil(today);
            if (aggregatedUntil != null && !start.isAfter(aggregatedUntil)) {
                for (ArticleTotals t : dailyRepository.sumByArticle(start, aggregatedUntil)) {
                    totals.computeIfAbsent(t.getArticleId(), id -> new Totals())
                            .add(nz(t.getViews()), nz(t.getUniqueReaders()), nz(t.getCompletedReads()));
                }
            }
            LocalDate liveFrom = aggregatedUntil == null || aggregatedUntil.isBefore(start)
                    ? start
                    : aggregatedUntil.plusDays(1);
            aggregationJob.computeRange(liveFrom.atStartOfDay(TimeZones.PARIS).toInstant(), inclusiveEnd(now), null)
                    .forEach(row -> add(totals, row));
        }

        // Exclut les articles supprimés et les ids absents de la table article
        Map<Long, Article> articles = articleRepository.findAllById(totals.keySet()).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity()));

        List<Map.Entry<Long, Totals>> ranked = totals.entrySet().stream()
                .filter(e -> e.getValue().views > 0)
                .filter(e -> articles.containsKey(e.getKey()) && !articles.get(e.getKey()).isDeleted())
                .sorted(Comparator.<Map.Entry<Long, Totals>>comparingLong(e -> e.getValue().views).reversed()
                        .thenComparing(Comparator.<Map.Entry<Long, Totals>>comparingLong(
                                e -> e.getValue().uniqueReaders).reversed())
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .toList();

        List<TopResponse.Item> items = new ArrayList<>(ranked.size());
        for (Map.Entry<Long, Totals> e : ranked) {
            Totals t = e.getValue();
            items.add(new TopResponse.Item(
                    items.size() + 1,
                    e.getKey(),
                    articles.get(e.getKey()).getTitle(),
                    t.views,
                    t.uniqueReaders,
                    completionRate(t.completed, t.views)));
        }
        return new TopResponse(period.value(), now, List.copyOf(items));
    }

    /**
     * Un point par jour de {@code from} à {@code to}, zéros compris. {@code articleId} null = tout le blog
     * (articles supprimés inclus : c'est du trafic réellement reçu).
     */
    @Cacheable(cacheNames = CacheConfig.TRENDS_CACHE)
    @Transactional(readOnly = true)
    public TrendsResponse getTrends(LocalDate from, LocalDate to, Long articleId, LocalDate today) {
        if (articleId != null && !articleRepository.existsById(articleId)) {
            throw new ArticleNotFoundException(articleId);
        }
        Map<LocalDate, long[]> byDay = new HashMap<>();

        // Jours agrégés
        LocalDate aggregatedUntil = aggregatedUntil(today);
        LocalDate lastAggregated = aggregatedUntil == null || to.isBefore(aggregatedUntil) ? to : aggregatedUntil;
        if (aggregatedUntil != null && !from.isAfter(lastAggregated)) {
            List<DayTotals> rows = articleId == null
                    ? dailyRepository.sumByDay(from, lastAggregated)
                    : dailyRepository.sumByDayForArticle(articleId, from, lastAggregated);
            for (DayTotals row : rows) {
                byDay.put(row.getDay(), new long[] {nz(row.getViews()), nz(row.getUniqueReaders())});
            }
        }

        // Jours pas encore agrégés (aujourd'hui, veille avant 00:05) : à la volée ; jours futurs : zéros
        LocalDate liveFrom = aggregatedUntil == null || aggregatedUntil.isBefore(from) ? from : aggregatedUntil.plusDays(1);
        LocalDate liveTo = to.isBefore(today) ? to : today;
        for (LocalDate day = liveFrom; !day.isAfter(liveTo); day = day.plusDays(1)) {
            long[] live = new long[2];
            for (DailyArticleStats row : aggregationJob.computeDay(day)) {
                if (articleId == null || Objects.equals(articleId, row.getArticleId())) {
                    live[0] += row.getViews();
                    live[1] += row.getUniqueReaders();
                }
            }
            byDay.put(day, live);
        }

        List<TrendsResponse.Point> points = new ArrayList<>();
        long totalViews = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            long[] values = byDay.getOrDefault(day, new long[2]);
            points.add(new TrendsResponse.Point(day, values[0], values[1]));
            totalViews += values[0];
        }
        return new TrendsResponse(from, to, articleId, totalViews, List.copyOf(points));
    }

    /** Dernier jour servi depuis l'agrégat (au plus hier) ; null si l'agrégat est vide. */
    private LocalDate aggregatedUntil(LocalDate today) {
        LocalDate last = aggregationJob.lastAggregatedDay();
        LocalDate yesterday = today.minusDays(1);
        return last == null || last.isBefore(yesterday) ? last : yesterday;
    }

    /** Moyenne arrondie à l'entier le plus proche ; 0 sans lecture retenue. */
    static long averageReadTime(long totalSeconds, long readCount) {
        return readCount == 0 ? 0 : Math.round((double) totalSeconds / readCount);
    }

    /** Lectures complètes / vues, arrondi HALF_UP à 2 décimales et plafonné à 1.00 ; 0 sans vue. */
    static double completionRate(long completed, long views) {
        if (views == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(completed)
                .divide(BigDecimal.valueOf(views), 2, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE)
                .doubleValue();
    }

    /** "now" inclus dans un intervalle semi-ouvert [from, to). */
    private static Instant inclusiveEnd(Instant now) {
        return now.plus(1, ChronoUnit.MICROS);
    }

    private static void add(Map<Long, Totals> totals, DailyArticleStats row) {
        totals.computeIfAbsent(row.getArticleId(), id -> new Totals())
                .add(row.getViews(), row.getUniqueReaders(), row.getCompletedReads());
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static final class Totals {
        private long views;
        private long uniqueReaders;
        private long completed;

        void add(long views, long uniqueReaders, long completed) {
            this.views += views;
            this.uniqueReaders += uniqueReaders;
            this.completed += completed;
        }
    }
}
