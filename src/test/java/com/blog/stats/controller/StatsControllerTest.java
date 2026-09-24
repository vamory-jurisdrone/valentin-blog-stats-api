package com.blog.stats.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.stats.dto.ArticleStatsResponse;
import com.blog.stats.dto.TopResponse;
import com.blog.stats.dto.TrendsResponse;
import com.blog.stats.exception.ArticleNotFoundException;
import com.blog.stats.service.StatsService;
import com.blog.stats.util.Period;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StatsController.class)
@AutoConfigureMockMvc(addFilters = false)
class StatsControllerTest {

    /** 2026-09-24 à 11:00, heure de Paris. */
    static final Instant NOW = Instant.parse("2026-09-24T09:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StatsService statsService;

    // --- /articles/{id} ---

    @Test
    void articleStatsDefaultsToAllPeriod() throws Exception {
        when(statsService.getArticleStats(42L, Period.ALL)).thenReturn(new ArticleStatsResponse(
                42L, "Débuter avec Spring Boot", "all", 1280, 954, 187, 0.41,
                Instant.parse("2026-09-23T18:04:11Z")));

        mvc.perform(get("/api/stats/articles/42"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.articleId").value(42))
                .andExpect(jsonPath("$.title").value("Débuter avec Spring Boot"))
                .andExpect(jsonPath("$.period").value("all"))
                .andExpect(jsonPath("$.views").value(1280))
                .andExpect(jsonPath("$.uniqueReaders").value(954))
                .andExpect(jsonPath("$.avgReadTimeSeconds").value(187))
                .andExpect(jsonPath("$.completionRate").value(0.41))
                .andExpect(jsonPath("$.lastViewedAt").value("2026-09-23T18:04:11Z"));
    }

    @Test
    void articleStatsPassesPeriod() throws Exception {
        when(statsService.getArticleStats(42L, Period.D30)).thenReturn(
                new ArticleStatsResponse(42L, "T", "30d", 0, 0, 0, 0.0, null));

        mvc.perform(get("/api/stats/articles/42").param("period", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("30d"))
                .andExpect(jsonPath("$.lastViewedAt").doesNotExist());
    }

    @Test
    void unknownArticleReturns404() throws Exception {
        when(statsService.getArticleStats(7L, Period.ALL)).thenThrow(new ArticleNotFoundException(7L));

        mvc.perform(get("/api/stats/articles/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("article 7 not found"))
                .andExpect(jsonPath("$.instance").value("/api/stats/articles/7"));
    }

    @Test
    void unknownPeriodReturns400() throws Exception {
        mvc.perform(get("/api/stats/articles/42").param("period", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("period must be one of 24h, 7d, 30d, 90d, all"));
        mvc.perform(get("/api/stats/top").param("period", "foo"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(statsService);
    }

    @Test
    void nonNumericIdReturns400() throws Exception {
        mvc.perform(get("/api/stats/articles/abc")).andExpect(status().isBadRequest());
    }

    // --- /top ---

    @Test
    void topUsesDefaultsAndReturnsPrdShape() throws Exception {
        when(statsService.getTop(Period.D7, 5, TODAY)).thenReturn(new TopResponse("7d",
                Instant.parse("2026-09-24T09:00:00Z"), List.of(
                        new TopResponse.Item(1, 42L, "Débuter avec Spring Boot", 512, 430, 0.47),
                        new TopResponse.Item(2, 17L, "Symfony vs Spring", 388, 301, 0.33))));

        mvc.perform(get("/api/stats/top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("7d"))
                .andExpect(jsonPath("$.generatedAt").value("2026-09-24T09:00:00Z"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].articleId").value(42))
                .andExpect(jsonPath("$.items[0].title").value("Débuter avec Spring Boot"))
                .andExpect(jsonPath("$.items[0].views").value(512))
                .andExpect(jsonPath("$.items[0].uniqueReaders").value(430))
                .andExpect(jsonPath("$.items[0].completionRate").value(0.47))
                .andExpect(jsonPath("$.items[1].rank").value(2));
    }

    @Test
    void topAcceptsLimitBounds() throws Exception {
        when(statsService.getTop(any(), anyInt(), any())).thenReturn(new TopResponse("24h", NOW, List.of()));

        mvc.perform(get("/api/stats/top").param("period", "24h").param("limit", "1")).andExpect(status().isOk());
        mvc.perform(get("/api/stats/top").param("limit", "50")).andExpect(status().isOk());
        verify(statsService).getTop(Period.H24, 1, TODAY);
        verify(statsService).getTop(Period.D7, 50, TODAY);
    }

    @Test
    void limitOutOfBoundsReturns400() throws Exception {
        mvc.perform(get("/api/stats/top").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("limit must be between 1 and 50"))
                .andExpect(jsonPath("$.instance").value("/api/stats/top"));
        mvc.perform(get("/api/stats/top").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("limit must be between 1 and 50"));
        mvc.perform(get("/api/stats/top").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("limit has an invalid value"));
        verifyNoInteractions(statsService);
    }

    // --- /trends ---

    @Test
    void trendsDefaultToLastThirtyDays() throws Exception {
        LocalDate from = TODAY.minusDays(29);
        when(statsService.getTrends(from, TODAY, null, TODAY)).thenReturn(new TrendsResponse(from, TODAY, null, 734,
                List.of(new TrendsResponse.Point(from, 210, 180))));

        mvc.perform(get("/api/stats/trends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-08-26"))
                .andExpect(jsonPath("$.to").value("2026-09-24"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"articleId\":null")))
                .andExpect(jsonPath("$.totalViews").value(734))
                .andExpect(jsonPath("$.points[0].date").value("2026-08-26"))
                .andExpect(jsonPath("$.points[0].views").value(210))
                .andExpect(jsonPath("$.points[0].uniqueReaders").value(180));
    }

    @Test
    void trendsPassExplicitRangeAndArticle() throws Exception {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 3);
        when(statsService.getTrends(from, to, 42L, TODAY)).thenReturn(new TrendsResponse(from, to, 42L, 0,
                List.of()));

        mvc.perform(get("/api/stats/trends").param("from", "2026-09-01").param("to", "2026-09-03")
                        .param("articleId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articleId").value(42));
    }

    @Test
    void trendsWithOnlyFromEndsToday() throws Exception {
        mvc.perform(get("/api/stats/trends").param("from", "2026-09-20")).andExpect(status().isOk());
        verify(statsService).getTrends(LocalDate.of(2026, 9, 20), TODAY, null, TODAY);
    }

    @Test
    void trendsWithOnlyToCoverThirtyDaysBeforeIt() throws Exception {
        mvc.perform(get("/api/stats/trends").param("to", "2026-01-30")).andExpect(status().isOk());
        verify(statsService).getTrends(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30), null, TODAY);
    }

    @Test
    void trendsFromAfterToReturns400() throws Exception {
        mvc.perform(get("/api/stats/trends").param("from", "2026-09-10").param("to", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from must be before or equal to to"));
        verify(statsService, never()).getTrends(any(), any(), any(), any());
    }

    @Test
    void trendsRangeLimitedTo366Days() throws Exception {
        mvc.perform(get("/api/stats/trends").param("from", "2025-01-01").param("to", "2026-01-01"))
                .andExpect(status().isOk());                                   // 366 jours inclus
        mvc.perform(get("/api/stats/trends").param("from", "2024-12-31").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("range must not exceed 366 days"));
        verify(statsService).getTrends(eq(LocalDate.of(2025, 1, 1)), eq(LocalDate.of(2026, 1, 1)), isNull(), any());
    }

    @Test
    void trendsBadDateReturns400() throws Exception {
        mvc.perform(get("/api/stats/trends").param("from", "2026-13-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from has an invalid value"));
        mvc.perform(get("/api/stats/trends").param("to", "24/09/2026"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(statsService);
    }

    @Test
    void trendsUnknownArticleReturns404() throws Exception {
        when(statsService.getTrends(any(), any(), eq(99L), any())).thenThrow(new ArticleNotFoundException(99L));

        mvc.perform(get("/api/stats/trends").param("articleId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("article 99 not found"));
    }
}
