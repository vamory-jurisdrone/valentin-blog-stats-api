package com.blog.stats.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

public record TrendsResponse(
        LocalDate from,
        LocalDate to,
        @JsonInclude(JsonInclude.Include.ALWAYS) Long articleId,
        long totalViews,
        List<Point> points) {

    public record Point(LocalDate date, long views, long uniqueReaders) {}
}
