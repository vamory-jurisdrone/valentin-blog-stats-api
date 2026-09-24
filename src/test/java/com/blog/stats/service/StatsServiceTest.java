package com.blog.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blog.stats.dto.ArticleStatsResponse;
import com.blog.stats.dto.TopResponse;
import com.blog.stats.entity.Article;
import com.blog.stats.entity.DailyArticleStats;
import com.blog.stats.exception.ArticleNotFoundException;
import com.blog.stats.repository.ArticleRepository;
import com.blog.stats.repository.DailyArticleStatsRepository;
import com.blog.stats.repository.EventQueryRepository;
import com.blog.stats.util.Period;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Règles de calcul (arrondis, plafonds, fusion agrégat + aujourd'hui) avec des dépôts simulés. */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    @Mock
    private ArticleRepository articleRepository;
    @Mock
    private EventQueryRepository eventQueryRepository;
    @Mock
    private DailyArticleStatsRepository dailyRepository;
    @Mock
    private AggregationJob aggregationJob;

    private StatsService service;

    @BeforeEach
    void setUp() {
        service = new StatsService(articleRepository, eventQueryRepository, dailyRepository, aggregationJob,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void completionRateIsRoundedHalfUpAndCapped() {
        assertThat(StatsService.completionRate(0, 0)).isZero();
        assertThat(StatsService.completionRate(3, 0)).isZero();
        assertThat(StatsService.completionRate(1, 8)).isEqualTo(0.13);   // 0.125 -> 0.13
        assertThat(StatsService.completionRate(1, 3)).isEqualTo(0.33);
        assertThat(StatsService.completionRate(2, 3)).isEqualTo(0.67);
        assertThat(StatsService.completionRate(7, 5)).isEqualTo(1.0);    // plafonné
    }

    @Test
    void averageReadTimeIsRoundedToNearestSecond() {
        assertThat(StatsService.averageReadTime(0, 0)).isZero();
        assertThat(StatsService.averageReadTime(485, 4)).isEqualTo(121);  // 121.25
        assertThat(StatsService.averageReadTime(243, 2)).isEqualTo(122);  // 121.5
    }

    @Test
    void unknownArticleThrowsNotFound() {
        when(articleRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getArticleStats(7L, Period.ALL))
                .isInstanceOf(ArticleNotFoundException.class)
                .hasMessage("article 7 not found");
    }

    @Test
    void knownArticleWithoutEventsReturnsZeros() {
        when(articleRepository.findById(7L)).thenReturn(Optional.of(article(7L, "Sans lecteur", false)));
        when(eventQueryRepository.aggregateViewsOf(eq(7L), any(), any())).thenReturn(List.of());
        when(eventQueryRepository.aggregateReadsOf(eq(7L), any(), any())).thenReturn(List.of());

        ArticleStatsResponse stats = service.getArticleStats(7L, Period.D30);

        assertThat(stats).isEqualTo(new ArticleStatsResponse(7L, "Sans lecteur", "30d", 0, 0, 0, 0.0, null));
        verify(eventQueryRepository).aggregateViewsOf(7L,
                Instant.parse("2026-08-25T22:00:00Z"), Instant.parse("2026-09-24T10:00:00.000001Z"));
    }

    @Test
    void topMergesAggregateWithTodayAndRanksDeterministically() {
        when(dailyRepository.sumByArticle(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 23)))
                .thenReturn(List.of(totals(1L, 10, 8, 4), totals(2L, 12, 12, 1), totals(3L, 6, 5, 0)));
        when(aggregationJob.lastAggregatedDay()).thenReturn(LocalDate.of(2026, 9, 23));
        when(aggregationJob.computeRange(Instant.parse("2026-09-23T22:00:00Z"),
                Instant.parse("2026-09-24T10:00:00.000001Z"), null))
                .thenReturn(List.of(row(3L, 4, 2, 1), row(4L, 10, 5, 2)));
        when(articleRepository.findAllById(any())).thenReturn(List.of(
                article(1L, "Un", false), article(2L, "Deux", true), article(3L, "Trois", false),
                article(4L, "Quatre", false)));

        TopResponse top = service.getTop(Period.D7, 5, TODAY);

        assertThat(top.period()).isEqualTo("7d");
        assertThat(top.generatedAt()).isEqualTo(NOW);
        // 1, 3 et 4 ont 10 vues : départage par lecteurs uniques, puis par id
        assertThat(top.items()).containsExactly(
                new TopResponse.Item(1, 1L, "Un", 10, 8, 0.4),
                new TopResponse.Item(2, 3L, "Trois", 10, 7, 0.1),
                new TopResponse.Item(3, 4L, "Quatre", 10, 5, 0.2));
    }

    @Test
    void topForRollingPeriodUsesRawEventsOnly() {
        when(aggregationJob.computeRange(Instant.parse("2026-09-23T10:00:00Z"),
                Instant.parse("2026-09-24T10:00:00.000001Z"), null))
                .thenReturn(List.of(row(1L, 3, 2, 3), row(9L, 50, 50, 0)));
        when(articleRepository.findAllById(any())).thenReturn(List.of(article(1L, "Un", false)));

        TopResponse top = service.getTop(Period.H24, 5, TODAY);

        assertThat(top.items()).containsExactly(new TopResponse.Item(1, 1L, "Un", 3, 2, 1.0));
        verify(dailyRepository, never()).sumByArticle(any(), any());
    }

    private static Article article(Long id, String title, boolean deleted) {
        return new Article(id, title, NOW, deleted, NOW);
    }

    private static DailyArticleStats row(Long articleId, int views, int uniqueReaders, int completed) {
        return DailyArticleStats.builder().articleId(articleId).day(TODAY)
                .views(views).uniqueReaders(uniqueReaders).completedReads(completed).build();
    }

    private static DailyArticleStatsRepository.ArticleTotals totals(Long id, long views, long readers, long completed) {
        return new DailyArticleStatsRepository.ArticleTotals() {
            @Override
            public Long getArticleId() {
                return id;
            }

            @Override
            public Long getViews() {
                return views;
            }

            @Override
            public Long getUniqueReaders() {
                return readers;
            }

            @Override
            public Long getCompletedReads() {
                return completed;
            }
        };
    }
}
