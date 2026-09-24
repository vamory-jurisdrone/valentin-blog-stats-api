package com.blog.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record ViewEventRequest(
        @Schema(example = "42") @NotNull @Positive Long articleId,
        @Schema(example = "3f1c2a9e-7b1d-4c55-9a51-2f0e8f3b6d10") @NotNull @Rfc9562Uuid UUID sessionId) {}
