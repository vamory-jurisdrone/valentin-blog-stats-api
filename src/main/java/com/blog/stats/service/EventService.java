package com.blog.stats.service;

import com.blog.stats.config.StatsProperties;
import com.blog.stats.dto.ReadEventRequest;
import com.blog.stats.dto.ViewEventRequest;
import com.blog.stats.entity.ArticleEvent;
import com.blog.stats.entity.EventType;
import com.blog.stats.repository.ArticleEventRepository;
import com.blog.stats.repository.ArticleRepository;
import com.blog.stats.util.BotDetector;
import com.blog.stats.util.TimeZones;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestion des événements envoyés par le front.
 * Les événements ignorés (bot, article inconnu ou supprimé, doublon) ne lèvent pas d'erreur :
 * le front reçoit toujours 202.
 * Chaque événement verrouille d'abord la ligne de l'article : le contrôle « déjà vu ? » puis l'écriture
 * sont ainsi atomiques, même pour deux requêtes simultanées de la même session.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final ArticleEventRepository eventRepository;
    private final ArticleRepository articleRepository;
    private final StatsProperties properties;
    private final Clock clock;

    /** Une vue par couple (session, article) sur la fenêtre de dédoublonnage (30 min). */
    @Transactional
    public void recordView(ViewEventRequest request, String userAgent) {
        if (!isTrackable(request.articleId(), userAgent)) {
            return;
        }
        Instant now = clock.instant();
        boolean duplicate = eventRepository.existsByTypeAndSessionIdAndArticleIdAndOccurredAtAfter(
                EventType.VIEW, request.sessionId(), request.articleId(), windowStart(now));
        if (duplicate) {
            log.debug("Vue en doublon ignorée pour l'article {}", request.articleId());
            return;
        }
        eventRepository.save(ArticleEvent.builder()
                .articleId(request.articleId())
                .sessionId(request.sessionId())
                .type(EventType.VIEW)
                .occurredAt(now)
                .build());
    }

    /**
     * Une lecture = une ligne READ : les envois intermédiaires (toutes les 30 s) et le sendBeacon final
     * mettent à jour la ligne en cours au lieu d'en créer une nouvelle (on garde les maxima).
     * La ligne en cours ne survit ni à minuit (heure de Paris), ni à une nouvelle vue enregistrée :
     * sinon une lecture serait comptée deux fois dans l'agrégat du jour, ou deux lectures partielles
     * fusionneraient en une lecture complète.
     */
    @Transactional
    public void recordRead(ReadEventRequest request, String userAgent) {
        if (!isTrackable(request.articleId(), userAgent)) {
            return;
        }
        Instant now = clock.instant();
        Instant since = readMergeStart(request.sessionId(), request.articleId(), now);
        Optional<ArticleEvent> current = eventRepository
                .findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        EventType.READ, request.sessionId(), request.articleId(), since);
        ArticleEvent event = current.orElseGet(() -> ArticleEvent.builder()
                .articleId(request.articleId())
                .sessionId(request.sessionId())
                .type(EventType.READ)
                .build());
        event.setTimeSpentSeconds(max(event.getTimeSpentSeconds(), request.timeSpentSeconds()));
        event.setScrollPercent(max(event.getScrollPercent(), request.scrollPercent()));
        event.setOccurredAt(now);
        eventRepository.save(event);
    }

    private boolean isTrackable(Long articleId, String userAgent) {
        if (BotDetector.isBot(userAgent)) {
            log.debug("Événement de bot ignoré : {}", userAgent);
            return false;
        }
        // Verrou pris même pour un article supprimé : c'est lui qui sérialise la suite de la transaction.
        boolean active = articleRepository.findByIdForUpdate(articleId)
                .filter(article -> !article.isDeleted())
                .isPresent();
        if (!active) {
            log.debug("Événement ignoré : article {} inconnu ou supprimé", articleId);
        }
        return active;
    }

    /** Début de la fusion des READ : max(maintenant − 30 min, minuit à Paris, dernière vue enregistrée). */
    private Instant readMergeStart(UUID sessionId, Long articleId, Instant now) {
        Instant midnight = now.atZone(TimeZones.PARIS).toLocalDate().atStartOfDay(TimeZones.PARIS).toInstant();
        Instant since = latest(windowStart(now), midnight);
        return eventRepository
                .findFirstByTypeAndSessionIdAndArticleIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        EventType.VIEW, sessionId, articleId, since)
                .map(ArticleEvent::getOccurredAt)
                .orElse(since);
    }

    private Instant windowStart(Instant now) {
        return now.minus(Duration.ofMinutes(properties.dedupWindowMinutes()));
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static int max(Integer previous, int current) {
        return previous == null ? current : Math.max(previous, current);
    }
}
