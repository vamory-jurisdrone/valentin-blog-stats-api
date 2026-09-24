package com.blog.stats.dto;

import java.time.Instant;

public record ArticleStatsResponse(
        Long articleId,
        String title,
        String period,
        long views,
        long uniqueReaders,
        long avgReadTimeSeconds,
        double completionRate,
        Instant lastViewedAt) {}
