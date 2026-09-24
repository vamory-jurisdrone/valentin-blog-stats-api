package com.blog.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.blog.stats.config.StatsProperties;
import com.blog.stats.dto.ReadEventRequest;
import com.blog.stats.dto.ViewEventRequest;
import com.blog.stats.entity.Article;
import com.blog.stats.entity.ArticleEvent;
import com.blog.stats.entity.EventType;
import com.blog.stats.repository.ArticleEventRepository;
import com.blog.stats.repository.ArticleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0 Safari/537.36";
    private static final String GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
    private static final long ARTICLE_ID = 42L;
    private static final UUID SESSION = UUID.fromString("3f1c2a9e-7b1d-4c55-9a51-2f0e8f3b6d10");

    @Mock
    private ArticleEventRepository eventRepository;

    @Mock
    private ArticleRepository articleRepository;

    private EventService service;

    @BeforeEach
    void setUp() {
        StatsProperties properties = new StatsProperties(null, null, null, 30, 13, 5);
        service = new EventService(eventRepository, articleRepository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --- VIEW ---

    @Test
    void recordsNewView() {
        activeArticle();

        service.recordView(new ViewEventRequest(ARTICLE_ID, SESSION), BROWSER);

        verify(eventRepository).existsByTypeAndSessionIdAndArticleIdAndOccurredAtAfter(
                EventType.VIEW, SESSION, ARTICLE_ID, NOW.minus(Duration.ofMinutes(30)));
        ArticleEvent saved = captureSaved();
        assertThat(saved.getType()).isEqualTo(EventType.VIEW);
        assertThat(saved.getArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(saved.getSessionId()).isEqualTo(SESSION);
        assertThat(saved.getOccurredAt()).isEqualTo(NOW);
        assertThat(saved.getTimeSpentSeconds()).isNull();
        assertThat(saved.getScrollPercent()).isNull();
    }

    /** La fenêtre est glissante : une vue d'il y a 29 min bloque, une vue d'il y a 31 min non. */
    @ParameterizedTest
    @CsvSource({"29, false", "31, true"})
    void dedupWindowBoundary(long minutesAgo, boolean counted) {
        activeArticle();
        Instant previousView = NOW.minus(Duration.ofMinutes(minutesAgo));
        when(eventRepository.existsByTypeAndSessionIdAndArticleIdAndOccurredAtAfter(
                eq(EventType.VIEW), eq(SESSION), eq(ARTICLE_ID), any()))
                .thenAnswer(inv -> previousView.isAfter(inv.getArgument(3)));

        service.recordView(new ViewEventRequest(ARTICLE_ID, SESSION), BROWSER);

        verify(eventRepository, counted ? times(1) : never()).save(any());
    }

    @Test
    void ignoresDuplicateView() {
        activeArticle();
        when(eventRepository.existsByTypeAndSessionIdAndArticleIdAndOccurredAtAfter(any(), any(), any(), any()))
                .thenReturn(true);

        service.recordView(new ViewEventRequest(ARTICLE_ID, SESSION), BROWSER);

        verify(eventRepository, never()).save(any());
    }

    @Test
    void ignoresViewFromBot() {
        service.recordView(new ViewEventRequest(ARTICLE_ID, SESSION), GOOGLEBOT);

        verifyNoInteractions(eventRepository, articleRepository);
    }

    @Test
    void ignoresViewForUnknownArticle() {
        when(articleRepository.findByIdForUpdate(ARTICLE_ID)).thenReturn(Optional.empty());

        service.recordView(new ViewEventRequest(ARTICLE_ID, SESSION), BROWSER);

        verifyNoInteractions(eventRepository);
    }

    @Test
    void ignoresViewForDeletedArticle() {
        when(articleRepository.findByIdForUpdate(ARTICLE_ID)).thenReturn(Optional.of(article(true)));

        service.recordView(new ViewEventRequest(ARTICLE_ID, SESSION), BROWSER);

        verifyNoInteractions(eventRepository);
    }

    @Test
    void curlAndMissingUserAgentAreCounted() {
        activeArticle();

        service.recordView(new ViewEventRequest(ARTICLE_ID, SESSION), "curl/8.7.1");
        service.recordView(new ViewEventRequest(ARTICLE_ID, SESSION), null);

        verify(eventRepository, times(2)).save(any());
    }

    // --- READ ---

    @Test
    void recordsNewRead() {
        activeArticle();

        service.recordRead(new ReadEventRequest(ARTICLE_ID, SESSION, 185, 94), BROWSER);

        // Pas de vue récente : la fusion part de maintenant − 30 min (midi à Paris, minuit est plus loin).
        verify(eventRepository).findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                EventType.READ, SESSION, ARTICLE_ID, NOW.minus(Duration.ofMinutes(30)));
        ArticleEvent saved = captureSaved();
        assertThat(saved.getId()).isNull();
        assertThat(saved.getType()).isEqualTo(EventType.READ);
        assertThat(saved.getArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(saved.getSessionId()).isEqualTo(SESSION);
        assertThat(saved.getTimeSpentSeconds()).isEqualTo(185);
        assertThat(saved.getScrollPercent()).isEqualTo(94);
        assertThat(saved.getOccurredAt()).isEqualTo(NOW);
    }

    /** Envoi intermédiaire puis sendBeacon final : même ligne, maxima conservés, horodatage rafraîchi. */
    @ParameterizedTest
    @CsvSource({
        // ancien temps, ancien scroll, nouveau temps, nouveau scroll, temps attendu, scroll attendu
        "60, 40, 90, 70, 90, 70",
        "120, 95, 30, 20, 120, 95",
        "100, 50, 80, 90, 100, 90"
    })
    void updatesRecentReadWithMaxValues(int oldTime, int oldScroll, int newTime, int newScroll,
                                        int expectedTime, int expectedScroll) {
        activeArticle();
        ArticleEvent existing = ArticleEvent.builder()
                .id(7L).articleId(ARTICLE_ID).sessionId(SESSION).type(EventType.READ)
                .timeSpentSeconds(oldTime).scrollPercent(oldScroll)
                .occurredAt(NOW.minus(Duration.ofSeconds(30)))
                .build();
        noViewSinceWindowStart();
        when(eventRepository.findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                eq(EventType.READ), eq(SESSION), eq(ARTICLE_ID), any()))
                .thenReturn(Optional.of(existing));

        service.recordRead(new ReadEventRequest(ARTICLE_ID, SESSION, newTime, newScroll), BROWSER);

        ArticleEvent saved = captureSaved();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getTimeSpentSeconds()).isEqualTo(expectedTime);
        assertThat(saved.getScrollPercent()).isEqualTo(expectedScroll);
        assertThat(saved.getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    void existingReadWithNullValuesTakesNewOnes() {
        activeArticle();
        ArticleEvent existing = ArticleEvent.builder()
                .id(8L).articleId(ARTICLE_ID).sessionId(SESSION).type(EventType.READ)
                .occurredAt(NOW.minus(Duration.ofMinutes(5)))
                .build();
        noViewSinceWindowStart();
        when(eventRepository.findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                eq(EventType.READ), eq(SESSION), eq(ARTICLE_ID), any()))
                .thenReturn(Optional.of(existing));

        service.recordRead(new ReadEventRequest(ARTICLE_ID, SESSION, 45, 60), BROWSER);

        assertThat(existing.getTimeSpentSeconds()).isEqualTo(45);
        assertThat(existing.getScrollPercent()).isEqualTo(60);
    }

    @Test
    void ignoresReadFromBot() {
        service.recordRead(new ReadEventRequest(ARTICLE_ID, SESSION, 185, 94), "Mozilla/5.0 HeadlessChrome/120.0");

        verifyNoInteractions(eventRepository, articleRepository);
    }

    @Test
    void ignoresReadForUnknownOrDeletedArticle() {
        when(articleRepository.findByIdForUpdate(ARTICLE_ID)).thenReturn(Optional.of(article(true)));

        service.recordRead(new ReadEventRequest(ARTICLE_ID, SESSION, 185, 94), BROWSER);

        verifyNoInteractions(eventRepository);
    }

    /** Juste après minuit (Paris), la lecture de 23:50 n'est plus fusionnable : la borne est minuit. */
    @Test
    void readMergeStartsAtParisMidnight() {
        Instant justAfterMidnight = Instant.parse("2026-09-24T22:10:00Z");     // 00:10 le 25 à Paris
        service = new EventService(eventRepository, articleRepository,
                new StatsProperties(null, null, null, 30, 13, 5), Clock.fixed(justAfterMidnight, ZoneOffset.UTC));
        activeArticle();

        service.recordRead(new ReadEventRequest(ARTICLE_ID, SESSION, 60, 50), BROWSER);

        Instant midnight = Instant.parse("2026-09-24T22:00:00Z");
        verify(eventRepository).findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                EventType.VIEW, SESSION, ARTICLE_ID, midnight);
        verify(eventRepository).findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                EventType.READ, SESSION, ARTICLE_ID, midnight);
    }

    /** Une vue enregistrée il y a 5 min ouvre une nouvelle lecture : la fusion part de cette vue. */
    @Test
    void readMergeStartsAtLatestStoredView() {
        activeArticle();
        Instant lastView = NOW.minus(Duration.ofMinutes(5));
        when(eventRepository.findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                EventType.VIEW, SESSION, ARTICLE_ID, NOW.minus(Duration.ofMinutes(30))))
                .thenReturn(Optional.of(ArticleEvent.builder().type(EventType.VIEW).occurredAt(lastView).build()));

        service.recordRead(new ReadEventRequest(ARTICLE_ID, SESSION, 10, 95), BROWSER);

        verify(eventRepository).findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                EventType.READ, SESSION, ARTICLE_ID, lastView);
        assertThat(captureSaved().getId()).isNull();
    }

    private void noViewSinceWindowStart() {
        when(eventRepository.findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                eq(EventType.VIEW), eq(SESSION), eq(ARTICLE_ID), any()))
                .thenReturn(Optional.empty());
    }

    private void activeArticle() {
        when(articleRepository.findByIdForUpdate(ARTICLE_ID)).thenReturn(Optional.of(article(false)));
    }

    private static Article article(boolean deleted) {
        return new Article(ARTICLE_ID, "Titre", NOW.minus(Duration.ofDays(30)), deleted, NOW);
    }

    private ArticleEvent captureSaved() {
        ArgumentCaptor<ArticleEvent> captor = ArgumentCaptor.forClass(ArticleEvent.class);
        verify(eventRepository).save(captor.capture());
        return captor.getValue();
    }
}
