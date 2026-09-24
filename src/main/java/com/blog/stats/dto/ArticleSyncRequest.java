package com.blog.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * Le titre est limité en caractères (points de code), comme VARCHAR(255) en utf8mb4 et Symfony :
 * {@code @Size} compterait en UTF-16 et refuserait 128 émojis.
 */
public record ArticleSyncRequest(
        @Schema(example = "Débuter avec Spring Boot", maxLength = 255)
                @NotBlank @CodePointLength(max = 255, message = "must be at most 255 characters") String title,
        @Schema(example = "2026-09-01T08:00:00+02:00",
                description = "Entre 1970-01-01 et maintenant + 1 jour, sinon 400") @NotNull OffsetDateTime createdAt) {}
