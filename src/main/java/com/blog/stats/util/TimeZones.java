package com.blog.stats.util;

import java.time.ZoneId;

public final class TimeZones {

    /** Les jours et périodes sont calculés en heure française ; le stockage reste en UTC. */
    public static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private TimeZones() {}
}
