package com.blog.stats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Agrégat par article et par jour calendaire (Europe/Paris). */
@Entity
@Table(name = "daily_article_stats")
@IdClass(DailyArticleStatsId.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyArticleStats {

    @Id
    @Column(name = "article_id")
    private Long articleId;

    @Id
    @Column(name = "day")
    private LocalDate day;

    @Column(nullable = false)
    private int views;

    @Column(name = "unique_readers", nullable = false)
    private int uniqueReaders;

    /** Somme des temps de lecture retenus (2–1800 s), pour recalculer la moyenne. */
    @Column(name = "total_read_time_seconds", nullable = false)
    private long totalReadTimeSeconds;

    /** Nombre de READ retenus dans la moyenne. */
    @Column(name = "read_count", nullable = false)
    private int readCount;

    @Column(name = "completed_reads", nullable = false)
    private int completedReads;
}
