package com.blog.stats.dto;

import java.time.Instant;
import java.util.List;

public record TopResponse(String period, Instant generatedAt, List<Item> items) {

    public record Item(int rank, Long articleId, String title, long views, long uniqueReaders, double completionRate) {}
}
