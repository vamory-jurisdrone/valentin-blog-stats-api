CREATE TABLE article (
    id         BIGINT       NOT NULL,
    title      VARCHAR(255) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    synced_at  DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE article_event (
    id                 BIGINT           NOT NULL AUTO_INCREMENT,
    article_id         BIGINT           NOT NULL,
    session_id         UUID             NOT NULL,
    type               VARCHAR(10)      NOT NULL,
    time_spent_seconds INT              NULL,
    scroll_percent     TINYINT UNSIGNED NULL,
    occurred_at        DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_event_article_occurred (article_id, occurred_at),
    INDEX idx_event_occurred (occurred_at),
    INDEX idx_event_dedup (session_id, article_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE daily_article_stats (
    article_id              BIGINT NOT NULL,
    day                     DATE   NOT NULL,
    views                   INT    NOT NULL DEFAULT 0,
    unique_readers          INT    NOT NULL DEFAULT 0,
    total_read_time_seconds BIGINT NOT NULL DEFAULT 0,
    read_count              INT    NOT NULL DEFAULT 0,
    completed_reads         INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (article_id, day),
    INDEX idx_daily_day (day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
