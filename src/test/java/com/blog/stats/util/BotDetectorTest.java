package com.blog.stats.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BotDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
        "Mozilla/5.0 (compatible; Yahoo! Slurp; http://help.yahoo.com/help/us/ysearch/slurp)",
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/120.0 Safari/537.36",
        "Mozilla/5.0 (compatible; Baiduspider/2.0; +http://www.baidu.com/search/spider.html)",
        "Mozilla/5.0 (compatible; Google Web Preview)",
        "Twitterbot/1.0",
        "WhatsApp/2.23.20.0",
        "Mozilla/5.0 (compatible; SemrushBot/7~bl; +http://www.semrush.com/bot.html)",
        "CCBot/2.0 (https://commoncrawl.org/faq/)"
    })
    void detectsKnownBots(String userAgent) {
        assertThat(BotDetector.isBot(userAgent)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.5; rv:129.0) Gecko/20100101 Firefox/129.0",
        "curl/8.7.1",
        "PostmanRuntime/7.39.0"
    })
    void keepsBrowsersAndManualTools(String userAgent) {
        assertThat(BotDetector.isBot(userAgent)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void missingUserAgentIsNotABot(String userAgent) {
        assertThat(BotDetector.isBot(userAgent)).isFalse();
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(BotDetector.isBot("SOME-CRAWLER/1.0")).isTrue();
    }
}
