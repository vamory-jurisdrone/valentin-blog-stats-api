package com.blog.stats.repository;

import com.blog.stats.entity.ArticleEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Requêtes d'agrégation sur les événements bruts (JPQL uniquement : MariaDB et H2).
 * Les intervalles sont semi-ouverts : {@code [from, to)}.
 */
public interface EventQueryRepository extends Repository<ArticleEvent, Long> {

    /** Vues déjà dédoublonnées à l'ingestion : un VIEW stocké = une vue. */
    interface ViewAggregate {
        Long getArticleId();

        Long getViews();

        Long getUniqueReaders();

        Instant getLastViewedAt();
    }

    /** readCount / totalReadTimeSeconds : READ retenus (2–1800 s) ; completedReads : scroll ≥ 90 et temps ≥ 30 s. */
    interface ReadAggregate {
        Long getArticleId();

        Long getReadCount();

        Long getTotalReadTimeSeconds();

        Long getCompletedReads();
    }

    String VIEW_SELECT = """
            select e.articleId as articleId,
                   count(e) as views,
                   count(distinct e.sessionId) as uniqueReaders,
                   max(e.occurredAt) as lastViewedAt
            from ArticleEvent e
            where e.type = com.blog.stats.entity.EventType.VIEW
              and e.occurredAt >= :from and e.occurredAt < :to
            """;

    String READ_SELECT = """
            select e.articleId as articleId,
                   sum(case when e.timeSpentSeconds between 2 and 1800 then 1 else 0 end) as readCount,
                   sum(case when e.timeSpentSeconds between 2 and 1800 then e.timeSpentSeconds else 0 end)
                       as totalReadTimeSeconds,
                   sum(case when e.scrollPercent >= 90 and e.timeSpentSeconds >= 30 then 1 else 0 end)
                       as completedReads
            from ArticleEvent e
            where e.type = com.blog.stats.entity.EventType.READ
              and e.occurredAt >= :from and e.occurredAt < :to
            """;

    @Query(VIEW_SELECT + " group by e.articleId")
    List<ViewAggregate> aggregateViews(@Param("from") Instant from, @Param("to") Instant to);

    @Query(VIEW_SELECT + " and e.articleId = :articleId group by e.articleId")
    List<ViewAggregate> aggregateViewsOf(@Param("articleId") Long articleId,
                                         @Param("from") Instant from, @Param("to") Instant to);

    @Query(READ_SELECT + " group by e.articleId")
    List<ReadAggregate> aggregateReads(@Param("from") Instant from, @Param("to") Instant to);

    @Query(READ_SELECT + " and e.articleId = :articleId group by e.articleId")
    List<ReadAggregate> aggregateReadsOf(@Param("articleId") Long articleId,
                                         @Param("from") Instant from, @Param("to") Instant to);

    /** Plus ancien événement, pour le rattrapage quand la table d'agrégats est vide. */
    @Query("select min(e.occurredAt) from ArticleEvent e")
    Instant findEarliestOccurredAt();

    /** Purge RGPD : événements bruts plus anciens que la date donnée. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ArticleEvent e where e.occurredAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
