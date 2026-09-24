package com.blog.stats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.stats.dto.ArticleSyncRequest;
import com.blog.stats.exception.BadRequestException;
import com.blog.stats.service.ArticleService;
import java.time.Instant;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Le JWT (scope articles:write) est vérifié par la sécurité, testée à part (SecurityConfigTest). */
@WebMvcTest(ArticleController.class)
@AutoConfigureMockMvc(addFilters = false)
class ArticleControllerTest {

    private static final String BODY =
            "{\"title\":\"Débuter avec Spring Boot\",\"createdAt\":\"2026-09-01T08:00:00+02:00\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ArticleService articleService;

    @Test
    void putReturns204() throws Exception {
        mvc.perform(put("/api/articles/42").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // Jackson ramène l'offset en UTC (spring.jackson.time-zone) : on compare l'instant.
        ArgumentCaptor<ArticleSyncRequest> captor = ArgumentCaptor.forClass(ArticleSyncRequest.class);
        verify(articleService).upsert(eq(42L), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("Débuter avec Spring Boot");
        assertThat(captor.getValue().createdAt().toInstant()).isEqualTo(Instant.parse("2026-09-01T06:00:00Z"));
    }

    @Test
    void putWithNonPositiveIdReturns400() throws Exception {
        doThrow(new BadRequestException("id must be positive")).when(articleService).upsert(
                eq(0L), any());

        mvc.perform(put("/api/articles/0").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("id must be positive"))
                .andExpect(jsonPath("$.instance").value("/api/articles/0"));
    }

    @Test
    void putWithNonNumericIdReturns400() throws Exception {
        mvc.perform(put("/api/articles/abc").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("id has an invalid value"));

        verifyNoInteractions(articleService);
    }

    @Test
    void putWithBlankTitleReturns400() throws Exception {
        mvc.perform(put("/api/articles/42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \",\"createdAt\":\"2026-09-01T08:00:00+02:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("title")));

        verifyNoInteractions(articleService);
    }

    @Test
    void putWithTooLongTitleReturns400() throws Exception {
        mvc.perform(putTitle("a".repeat(256)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("title must be at most 255 characters"));

        verifyNoInteractions(articleService);
    }

    /** La limite compte des caractères (points de code) : 255 émojis = 510 unités UTF-16, acceptés. */
    @Test
    void putWith255EmojisIsAccepted() throws Exception {
        String title = "🚀".repeat(255);

        mvc.perform(putTitle(title)).andExpect(status().isNoContent());

        ArgumentCaptor<ArticleSyncRequest> captor = ArgumentCaptor.forClass(ArticleSyncRequest.class);
        verify(articleService).upsert(eq(42L), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo(title);
    }

    @Test
    void putWith256EmojisReturns400() throws Exception {
        mvc.perform(putTitle("🚀".repeat(256)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("title must be at most 255 characters"));

        verifyNoInteractions(articleService);
    }

    private static MockHttpServletRequestBuilder putTitle(String title) {
        return put("/api/articles/42").contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8")
                .content("{\"title\":\"" + title + "\",\"createdAt\":\"2026-09-01T08:00:00+02:00\"}");
    }

    @Test
    void putWithoutCreatedAtReturns400() throws Exception {
        mvc.perform(put("/api/articles/42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Titre\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("createdAt must not be null"));
    }

    /** La date se parse (OffsetDateTime va jusqu'à l'an 999999999) : c'est le service qui la refuse. */
    @Test
    void putWithCreatedAtOutOfRangeReturns400() throws Exception {
        doThrow(new BadRequestException("createdAt is out of range")).when(articleService).upsert(eq(42L), any());

        mvc.perform(put("/api/articles/42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Titre\",\"createdAt\":\"+99999-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("createdAt is out of range"));

        ArgumentCaptor<ArticleSyncRequest> captor = ArgumentCaptor.forClass(ArticleSyncRequest.class);
        verify(articleService).upsert(eq(42L), captor.capture());
        assertThat(captor.getValue().createdAt().getYear()).isEqualTo(99999);
    }

    /** Une IllegalArgumentException n'est plus une erreur client : 500 sans détail. */
    @Test
    void unexpectedIllegalArgumentReturns500() throws Exception {
        doThrow(new IllegalArgumentException("boom")).when(articleService).softDelete(42L);

        mvc.perform(delete("/api/articles/42"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Internal error"));
    }

    @Test
    void putWithMalformedJsonReturns400() throws Exception {
        mvc.perform(put("/api/articles/42").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed JSON body"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/articles/42"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(articleService).softDelete(42L);
    }

    @Test
    void deleteWithNonPositiveIdReturns400() throws Exception {
        doThrow(new BadRequestException("id must be positive")).when(articleService).softDelete(-1L);

        mvc.perform(delete("/api/articles/-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("id must be positive"));
    }
}
