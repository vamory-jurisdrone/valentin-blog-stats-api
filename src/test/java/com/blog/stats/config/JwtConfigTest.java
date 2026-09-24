package com.blog.stats.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blog.stats.StatsApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class JwtConfigTest {

    private static StatsProperties properties(String jwtSecret, String clientSecret) {
        return new StatsProperties(
                new StatsProperties.Auth(jwtSecret, 15,
                        List.of(new StatsProperties.Client("symfony-blog", clientSecret, Scopes.ALL))),
                null, null, 30, 13, 5);
    }

    @Test
    void secretMustBeAtLeast32Bytes() {
        assertThat(JwtConfig.signingKey(properties("0123456789abcdef0123456789abcdef", "x")).getEncoded())
                .hasSize(32);
        assertThatThrownBy(() -> JwtConfig.signingKey(properties("0123456789abcdef0123456789abcde", "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STATS_JWT_SECRET")
                .hasMessageContaining("at least 32 bytes")
                .hasMessageContaining("got 31");
        // Octets et non caractères : 16 « é » = 32 octets UTF-8
        assertThat(JwtConfig.signingKey(properties("é".repeat(16), "x")).getEncoded()).hasSize(32);
        assertThatThrownBy(() -> JwtConfig.signingKey(properties(null, "x"))).hasMessageContaining("got 0");
        assertThatThrownBy(() -> JwtConfig.signingKey(new StatsProperties(null, null, null, 30, 13, 5)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applicationRefusesToStartWithAShortSecret() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(StatsApplication.class)
                .profiles("local")
                .run("--server.port=0", "--stats.auth.jwt-secret=too-short-secret",
                        "--spring.datasource.url=jdbc:h2:mem:short-secret;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stats.auth.jwt-secret (STATS_JWT_SECRET) must be at least 32 bytes");
    }

    @Test
    void defaultSecretsAreReported() {
        assertThat(JwtConfig.insecureSettings(properties(JwtConfig.DEFAULT_JWT_SECRET, "change-me").auth()))
                .hasSize(2)
                .anyMatch(p -> p.contains("jwt-secret"))
                .anyMatch(p -> p.contains("symfony-blog"));
        assertThat(JwtConfig.insecureSettings(properties("a-strong-secret-of-more-than-32-bytes!!", "short")
                .auth())).hasSize(1);
        assertThat(JwtConfig.insecureSettings(properties("a-strong-secret-of-more-than-32-bytes!!",
                "9f86d081884c7d659a2feaa0c55ad015").auth())).isEmpty();
        assertThat(JwtConfig.insecureSettings(null)).isEmpty();
    }

    @Test
    void defaultSecretsLogAVisibleErrorAtStartup(CapturedOutput output) {
        new JwtConfig(properties(JwtConfig.DEFAULT_JWT_SECRET, "change-me")).warnAboutInsecureSecrets();
        assertThat(output).contains("ERROR").contains("INSECURE AUTH SETTINGS")
                .contains("STATS_CLIENT_SECRET").contains("STATS_JWT_SECRET")
                .contains("stats.auth.jwt-secret is the default value");
    }

    @Test
    void strongSecretsLogNothing(CapturedOutput output) {
        new JwtConfig(properties("a-strong-secret-of-more-than-32-bytes!!", "9f86d081884c7d659a2feaa0c55ad015"))
                .warnAboutInsecureSecrets();
        assertThat(output).doesNotContain("INSECURE AUTH SETTINGS");
    }
}
