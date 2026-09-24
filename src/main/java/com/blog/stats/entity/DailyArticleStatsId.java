package com.blog.stats.entity;

import java.io.Serializable;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class DailyArticleStatsId implements Serializable {

    private Long articleId;
    private LocalDate day;
}
