package com.blog.stats.util;

import java.util.regex.Pattern;

/**
 * Repère les User-Agent de robots, crawlers et générateurs d'aperçus de liens.
 * curl et Postman ne sont volontairement pas considérés comme des bots (tests manuels).
 */
public final class BotDetector {

    private static final Pattern BOT_PATTERN = Pattern.compile(
            "bot|crawl|spider|slurp|facebookexternalhit|facebookcatalog|headless|preview|"
                    + "lighthouse|phantomjs|mediapartners|embedly|whatsapp|pingdom|uptime|"
                    + "chatgpt-user|perplexity|ia_archiver|archive\\.org",
            Pattern.CASE_INSENSITIVE);

    private BotDetector() {}

    /** User-Agent absent : on ne peut rien conclure, l'événement est gardé. */
    public static boolean isBot(String userAgent) {
        return userAgent != null && !userAgent.isBlank() && BOT_PATTERN.matcher(userAgent).find();
    }
}
