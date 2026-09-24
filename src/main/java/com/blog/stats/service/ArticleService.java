package com.blog.stats.service;

import com.blog.stats.config.CacheConfig;
import com.blog.stats.dto.ArticleSyncRequest;
import com.blog.stats.entity.Article;
import com.blog.stats.exception.BadRequestException;
import com.blog.stats.repository.ArticleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Synchronisation de la copie des articles Symfony (titre, date de création, suppression logique). */
@Service
@RequiredArgsConstructor
public class ArticleService {

    /** Tolérance sur l'horloge de Symfony pour une date de création « dans le futur ». */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofDays(1);

    private final ArticleRepository articleRepository;
    private final Clock clock;

    /**
     * Crée ou met à jour l'article ; un nouveau PUT restaure un article supprimé.
     * Titre et suppression apparaissent dans /top et /trends : leurs caches sont vidés.
     */
    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.TOP_CACHE, CacheConfig.TRENDS_CACHE}, allEntries = true)
    public void upsert(Long id, ArticleSyncRequest request) {
        requirePositive(id);
        Instant now = clock.instant();
        Instant createdAt = requireInRange(request.createdAt().toInstant(), now);
        Article article = articleRepository.findById(id).orElseGet(() -> {
            Article created = new Article();
            created.setId(id);
            return created;
        });
        article.setTitle(request.title());
        article.setCreatedAt(createdAt);
        article.setDeleted(false);
        article.setSyncedAt(now);
        articleRepository.save(article);
    }

    /** Suppression logique, idempotente : les stats sont conservées, un id inconnu est ignoré. */
    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.TOP_CACHE, CacheConfig.TRENDS_CACHE}, allEntries = true)
    public void softDelete(Long id) {
        requirePositive(id);
        articleRepository.findById(id)
                .filter(article -> !article.isDeleted())
                .ifPresent(article -> {
                    article.setDeleted(true);
                    articleRepository.save(article);
                });
    }

    private static void requirePositive(Long id) {
        if (id == null || id <= 0) {
            throw new BadRequestException("id must be positive");
        }
    }

    /** Une date absurde (an 99999…) ferait déborder le DATETIME de MariaDB : 400 plutôt que 500. */
    private static Instant requireInRange(Instant createdAt, Instant now) {
        if (createdAt.isBefore(Instant.EPOCH) || createdAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw new BadRequestException("createdAt is out of range");
        }
        return createdAt;
    }
}
