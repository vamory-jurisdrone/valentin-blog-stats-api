package com.blog.stats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Copie minimale d'un article Symfony, remplie par PUT /api/articles/{id}. */
@Entity
@Table(name = "article")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Article {

    /** Même id que Symfony : jamais généré ici. */
    @Id
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "synced_at")
    private Instant syncedAt;
}
