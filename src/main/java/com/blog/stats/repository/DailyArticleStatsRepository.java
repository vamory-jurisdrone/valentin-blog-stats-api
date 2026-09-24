package com.blog.stats.repository;

import com.blog.stats.entity.DailyArticleStats;
import com.blog.stats.entity.DailyArticleStatsId;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyArticleStatsRepository extends JpaRepository<DailyArticleStats, DailyArticleStatsId> {

    /** Totaux par article sur un intervalle de jours (bornes incluses). */
    interface ArticleTotals {
        Long getArticleId();

        Long getViews();

        Long getUniqueReaders();

        Long getCompletedReads();
    }

    /** Totaux par jour (tous articles confondus ou un seul). */
    interface DayTotals {
        LocalDate getDay();

        Long getViews();

        Long getUniqueReaders();
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DailyArticleStats d where d.day = :day")
    int deleteByDay(@Param("day") LocalDate day);

    @Query("select max(d.day) from DailyArticleStats d")
    LocalDate findMaxDay();

    @Query("""
            select d.articleId as articleId, sum(d.views) as views,
                   sum(d.uniqueReaders) as uniqueReaders, sum(d.completedReads) as completedReads
            from DailyArticleStats d
            where d.day >= :from and d.day <= :to
            group by d.articleId
            """)
    List<ArticleTotals> sumByArticle(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select d.day as day, sum(d.views) as views, sum(d.uniqueReaders) as uniqueReaders
            from DailyArticleStats d
            where d.day >= :from and d.day <= :to
            group by d.day
            """)
    List<DayTotals> sumByDay(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select d.day as day, sum(d.views) as views, sum(d.uniqueReaders) as uniqueReaders
            from DailyArticleStats d
            where d.articleId = :articleId and d.day >= :from and d.day <= :to
            group by d.day
            """)
    List<DayTotals> sumByDayForArticle(@Param("articleId") Long articleId,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);
}
