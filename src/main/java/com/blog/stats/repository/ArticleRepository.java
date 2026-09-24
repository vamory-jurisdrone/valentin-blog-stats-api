package com.blog.stats.repository;

import com.blog.stats.entity.Article;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    /**
     * SELECT … FOR UPDATE sur la ligne de l'article : l'ingestion sérialise ainsi les événements
     * d'un même article (sinon deux vues simultanées passent toutes les deux le dédoublonnage).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Article a where a.id = :id")
    Optional<Article> findByIdForUpdate(@Param("id") Long id);
}
