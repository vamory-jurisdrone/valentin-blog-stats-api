package com.blog.stats.service;

import com.blog.stats.config.StatsProperties;
import com.blog.stats.entity.DailyArticleStats;
import com.blog.stats.repository.DailyArticleStatsRepository;
import com.blog.stats.repository.EventQueryRepository;
import com.blog.stats.repository.EventQueryRepository.ReadAggregate;
import com.blog.stats.repository.EventQueryRepository.ViewAggregate;
import com.blog.stats.util.TimeZones;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Remplit {@code daily_article_stats} depuis {@code article_event} (un jour calendaire Europe/Paris
 * par ligne) et purge les événements bruts au-delà de la durée de conservation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggregationJob {

    /** Ordre du rattrapage au démarrage : avant le jeu de démo (profil demo). */
    public static final int CATCH_UP_ORDER = 0;

    /**
     * Jours récents recalculés à chaque passage : répare un passage de nuit raté
     * et intègre les événements arrivés en retard.
     */
    public static final int REAGGREGATED_DAYS = 3;

    private final EventQueryRepository eventQueryRepository;
    private final DailyArticleStatsRepository dailyRepository;
    private final StatsProperties properties;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final CacheManager cacheManager;

    /**
     * Chaque nuit à 00:05 (Paris) : recalcule les {@value #REAGGREGATED_DAYS} derniers jours,
     * puis purge les vieux événements. Un échec de l'un n'empêche pas l'autre.
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "Europe/Paris")
    public void runNightly() {
        LocalDate yesterday = today().minusDays(1);
        LocalDate from = yesterday.minusDays(REAGGREGATED_DAYS - 1L);
        try {
            backfill(from, yesterday);
            log.info("Nightly aggregation done for {} to {}", from, yesterday);
        } catch (RuntimeException ex) {
            log.error("Nightly aggregation failed for {} to {}", from, yesterday, ex);
        } finally {
            clearStatsCaches();
        }
        try {
            log.info("{} expired events purged", purgeExpiredEvents());
        } catch (RuntimeException ex) {
            log.error("Purge of expired events failed", ex);
        }
    }

    /** Dernier jour présent dans l'agrégat (null si vide) : les jours suivants sont calculés à la volée. */
    public LocalDate lastAggregatedDay() {
        return dailyRepository.findMaxDay();
    }

    /**
     * Recalcule un jour depuis les événements bruts et remplace ses lignes (idempotent).
     * Transaction programmatique : fonctionne aussi en appel interne (backfill, rattrapage).
     */
    @Transactional
    public void aggregateDay(LocalDate day) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<DailyArticleStats> rows = computeDay(day);
            dailyRepository.deleteByDay(day);
            dailyRepository.saveAll(rows);
        });
    }

    /** Agrège chaque jour de {@code from} à {@code to} inclus, un jour par transaction. */
    public void backfill(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            aggregateDay(day);
        }
    }

    /**
     * Au démarrage, agrège les jours passés manquants (serveur arrêté à 00:05, par exemple)
     * et recalcule toujours les {@value #REAGGREGATED_DAYS} derniers jours,
     * dans la limite de la durée de conservation.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(CATCH_UP_ORDER)
    public void catchUp() {
        try {
            LocalDate today = today();
            LocalDate yesterday = today.minusDays(1);
            LocalDate start;
            LocalDate maxDay = dailyRepository.findMaxDay();
            if (maxDay != null) {
                LocalDate recent = yesterday.minusDays(REAGGREGATED_DAYS - 1L);
                start = maxDay.plusDays(1).isBefore(recent) ? maxDay.plusDays(1) : recent;
            } else {
                Instant earliest = eventQueryRepository.findEarliestOccurredAt();
                if (earliest == null) {
                    return;
                }
                start = LocalDate.ofInstant(earliest, TimeZones.PARIS);
            }
            LocalDate retentionStart = today.minusMonths(properties.retentionMonths());
            if (start.isBefore(retentionStart)) {
                start = retentionStart;
            }
            if (start.isAfter(yesterday)) {
                return;
            }
            backfill(start, yesterday);
            clearStatsCaches();
            log.info("Aggregation catch-up: {} to {}", start, yesterday);
        } catch (RuntimeException ex) {
            // Un échec ici ne doit pas empêcher l'API de démarrer : le job de nuit repassera.
            log.error("Aggregation catch-up failed", ex);
        }
    }

    /** Supprime les événements bruts antérieurs à aujourd'hui − retention-months (Paris). */
    public int purgeExpiredEvents() {
        Instant cutoff = today().minusMonths(properties.retentionMonths())
                .atStartOfDay(TimeZones.PARIS).toInstant();
        Integer purged = new TransactionTemplate(transactionManager)
                .execute(status -> eventQueryRepository.deleteOlderThan(cutoff));
        return purged == null ? 0 : purged;
    }

    /** Lignes (non persistées) d'un jour calendaire Paris ; gère les jours de 23 h / 25 h. */
    public List<DailyArticleStats> computeDay(LocalDate day) {
        Instant from = day.atStartOfDay(TimeZones.PARIS).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(TimeZones.PARIS).toInstant();
        return computeRange(from, to, day);
    }

    /** Agrégat par article sur {@code [from, to)} ; {@code day} n'est qu'une étiquette (peut être null). */
    public List<DailyArticleStats> computeRange(Instant from, Instant to, LocalDate day) {
        Map<Long, DailyArticleStats> byArticle = new TreeMap<>();
        for (ViewAggregate v : eventQueryRepository.aggregateViews(from, to)) {
            DailyArticleStats row = byArticle.computeIfAbsent(v.getArticleId(), id -> emptyRow(id, day));
            row.setViews(toInt(v.getViews()));
            row.setUniqueReaders(toInt(v.getUniqueReaders()));
        }
        for (ReadAggregate r : eventQueryRepository.aggregateReads(from, to)) {
            DailyArticleStats row = byArticle.computeIfAbsent(r.getArticleId(), id -> emptyRow(id, day));
            row.setReadCount(toInt(r.getReadCount()));
            row.setTotalReadTimeSeconds(r.getTotalReadTimeSeconds() == null ? 0 : r.getTotalReadTimeSeconds());
            row.setCompletedReads(toInt(r.getCompletedReads()));
        }
        return new ArrayList<>(byArticle.values());
    }

    /** Après une agrégation, /top et /trends ne doivent pas resservir des chiffres calculés avant. */
    public void clearStatsCaches() {
        cacheManager.getCacheNames().forEach(name -> Optional.ofNullable(cacheManager.getCache(name))
                .ifPresent(cache -> cache.clear()));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), TimeZones.PARIS);
    }

    private static DailyArticleStats emptyRow(Long articleId, LocalDate day) {
        return DailyArticleStats.builder().articleId(articleId).day(day).build();
    }

    private static int toInt(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }
}
