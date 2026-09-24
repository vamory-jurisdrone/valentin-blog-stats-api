package com.blog.stats.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.stats.dto.TopResponse;
import com.blog.stats.entity.ArticleEvent;
import com.blog.stats.entity.EventType;
import com.blog.stats.repository.ArticleEventRepository;
import com.blog.stats.repository.ArticleRepository;
import com.blog.stats.repository.DailyArticleStatsRepository;
import com.blog.stats.service.StatsService;
import com.blog.stats.support.MutableClock;
import com.blog.stats.util.Period;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:stats-demo;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=DAY")
@ActiveProfiles({"local", "demo"})
class DemoDataSeederTest {

    static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
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
    private DemoDataSeeder seeder;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleEventRepository eventRepository;
    @Autowired
    private DailyArticleStatsRepository dailyRepository;
    @Autowired
    private StatsService statsService;

    @Test
    void seedsArticlesEventsAndAggregatesOnStartup() {
        assertThat(articleRepository.count()).isEqualTo(8);
        assertThat(articleRepository.findById(DemoDataSeeder.DELETED_ARTICLE_ID).orElseThrow().isDeleted()).isTrue();
        List<ArticleEvent> events = eventRepository.findAll();
        assertThat(events).hasSizeGreaterThan(2_000);
        assertThat(events).allMatch(e -> !e.getOccurredAt().isAfter(NOW));
        assertThat(dailyRepository.findMaxDay()).isEqualTo(TODAY.minusDays(1));

        // Pas deux vues d'une même session sur un même article à moins de 30 min
        Map<String, List<Instant>> views = events.stream()
                .filter(e -> e.getType() == EventType.VIEW)
                .collect(Collectors.groupingBy(e -> e.getSessionId() + "/" + e.getArticleId(),
                        Collectors.mapping(ArticleEvent::getOccurredAt, Collectors.toList())));
        views.values().forEach(list -> {
            list.sort(Comparator.naturalOrder());
            for (int i = 1; i < list.size(); i++) {
                assertThat(Duration.between(list.get(i - 1), list.get(i))).isGreaterThanOrEqualTo(Duration.ofMinutes(30));
            }
        });
        assertThat(events).filteredOn(e -> e.getType() == EventType.READ)
                .allMatch(e -> e.getTimeSpentSeconds() <= 1800 && e.getScrollPercent() <= 100);

        TopResponse top = statsService.getTop(Period.D30, 10, TODAY);
        assertThat(top.items()).hasSize(7);
        assertThat(top.items()).noneMatch(i -> i.articleId() == DemoDataSeeder.DELETED_ARTICLE_ID);
        assertThat(top.items().get(0).articleId()).isEqualTo(1L);
    }

    @Test
    void seedingTwiceIsNoop() {
        long events = eventRepository.count();
        seeder.seed();
        assertThat(eventRepository.count()).isEqualTo(events);
    }

    @Test
    void generatedSessionIdsAreRfc9562V4() {
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            UUID id = DemoDataSeeder.randomUuidV4(random);
            assertThat(id.version()).isEqualTo(4);
            assertThat(id.variant()).isEqualTo(2);
        }
    }
}
