package com.blog.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.blog.stats.dto.ArticleSyncRequest;
import com.blog.stats.entity.Article;
import com.blog.stats.exception.BadRequestException;
import com.blog.stats.repository.ArticleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArticleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final ArticleSyncRequest REQUEST = new ArticleSyncRequest(
            "Débuter avec Spring Boot", OffsetDateTime.parse("2026-09-01T08:00:00+02:00"));

    @Mock
    private ArticleRepository articleRepository;

    private ArticleService service;

    @BeforeEach
    void setUp() {
        service = new ArticleService(articleRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void upsertCreatesArticleWithUtcCreatedAt() {
        when(articleRepository.findById(42L)).thenReturn(Optional.empty());

        service.upsert(42L, REQUEST);

        Article saved = captureSaved();
        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(saved.getTitle()).isEqualTo("Débuter avec Spring Boot");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-09-01T06:00:00Z"));
        assertThat(saved.isDeleted()).isFalse();
        assertThat(saved.getSyncedAt()).isEqualTo(NOW);
    }

    @Test
    void upsertUpdatesAndRestoresDeletedArticle() {
        Article existing = new Article(42L, "Ancien titre", Instant.parse("2020-01-01T00:00:00Z"), true,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(articleRepository.findById(42L)).thenReturn(Optional.of(existing));

        service.upsert(42L, REQUEST);

        Article saved = captureSaved();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getTitle()).isEqualTo("Débuter avec Spring Boot");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-09-01T06:00:00Z"));
        assertThat(saved.isDeleted()).isFalse();
        assertThat(saved.getSyncedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void upsertRejectsNonPositiveId(Long id) {
        assertThatThrownBy(() -> service.upsert(id, REQUEST))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("id must be positive");
        verifyNoInteractions(articleRepository);
    }

    /** Bornes : 1970-01-01 et maintenant + 1 jour inclus ; au-delà, 400 avant tout accès à la base. */
    @ParameterizedTest
    @ValueSource(strings = {"1969-12-31T23:59:59Z", "2026-09-25T10:00:00.000001Z", "+99999-01-01T00:00:00Z"})
    void upsertRejectsCreatedAtOutOfRange(String createdAt) {
        ArticleSyncRequest request = new ArticleSyncRequest("Titre", OffsetDateTime.parse(createdAt));

        assertThatThrownBy(() -> service.upsert(42L, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("createdAt is out of range");
        verifyNoInteractions(articleRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1970-01-01T00:00:00Z", "2026-09-25T10:00:00Z", "2026-09-25T12:00:00+02:00"})
    void upsertAcceptsCreatedAtOnTheBounds(String createdAt) {
        when(articleRepository.findById(42L)).thenReturn(Optional.empty());

        service.upsert(42L, new ArticleSyncRequest("Titre", OffsetDateTime.parse(createdAt)));

        assertThat(captureSaved().getCreatedAt()).isEqualTo(OffsetDateTime.parse(createdAt).toInstant());
    }

    @Test
    void softDeleteMarksArticleDeletedAndKeepsOtherFields() {
        Article existing = new Article(42L, "Titre", Instant.parse("2026-09-01T06:00:00Z"), false,
                Instant.parse("2026-09-02T00:00:00Z"));
        when(articleRepository.findById(42L)).thenReturn(Optional.of(existing));

        service.softDelete(42L);

        Article saved = captureSaved();
        assertThat(saved.isDeleted()).isTrue();
        assertThat(saved.getTitle()).isEqualTo("Titre");
        assertThat(saved.getSyncedAt()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
    }

    @Test
    void softDeleteOfUnknownArticleIsNoOp() {
        when(articleRepository.findById(99L)).thenReturn(Optional.empty());

        service.softDelete(99L);

        verify(articleRepository, never()).save(any());
    }

    @Test
    void softDeleteIsIdempotent() {
        Article deleted = new Article(42L, "Titre", Instant.parse("2026-09-01T06:00:00Z"), true, null);
        when(articleRepository.findById(42L)).thenReturn(Optional.of(deleted));

        service.softDelete(42L);

        verify(articleRepository, never()).save(any());
        assertThat(deleted.isDeleted()).isTrue();
    }

    @Test
    void softDeleteRejectsNonPositiveId() {
        assertThatThrownBy(() -> service.softDelete(0L)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(articleRepository);
    }

    private Article captureSaved() {
        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleRepository).save(captor.capture());
        return captor.getValue();
    }
}
