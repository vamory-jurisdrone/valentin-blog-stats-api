package com.blog.stats.util;

import com.blog.stats.exception.BadRequestException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;

/**
 * Périodes acceptées par les endpoints de stats.
 * <ul>
 *   <li>{@code 24h} : fenêtre glissante des 24 dernières heures.</li>
 *   <li>{@code 7d}, {@code 30d}, {@code 90d} : N jours calendaires (Europe/Paris), aujourd'hui inclus.</li>
 *   <li>{@code all} : pas de borne.</li>
 * </ul>
 */
public enum Period {
    H24("24h", 0),
    D7("7d", 7),
    D30("30d", 30),
    D90("90d", 90),
    ALL("all", 0);

    private final String value;
    private final int days;

    Period(String value, int days) {
        this.value = value;
        this.days = days;
    }

    public String value() {
        return value;
    }

    /** @throws BadRequestException si la valeur est inconnue (renvoyé en 400). */
    public static Period fromValue(String value) {
        return Arrays.stream(values())
                .filter(p -> p.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "period must be one of 24h, 7d, 30d, 90d, all"));
    }

    public boolean isRolling() {
        return this == H24;
    }

    /** Premier jour inclus (Europe/Paris) pour 7d/30d/90d ; null pour all et 24h. */
    public LocalDate startDay(LocalDate today) {
        return days > 0 ? today.minusDays(days - 1L) : null;
    }

    /** Premier instant inclus ; null pour all. */
    public Instant startInstant(Instant now) {
        if (this == ALL) {
            return null;
        }
        if (this == H24) {
            return now.minusSeconds(24 * 3600);
        }
        LocalDate today = ZonedDateTime.ofInstant(now, TimeZones.PARIS).toLocalDate();
        return startDay(today).atStartOfDay(TimeZones.PARIS).toInstant();
    }

    @Override
    public String toString() {
        return value;
    }
}
