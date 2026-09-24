package com.blog.stats.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.stats.dto.ReadEventRequest;
import com.blog.stats.dto.ViewEventRequest;
import com.blog.stats.service.EventService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Les filtres de sécurité (JWT, rate limit, CORS) sont testés à part (SecurityConfigTest). */
@WebMvcTest(EventController.class)
@AutoConfigureMockMvc(addFilters = false)
class EventControllerTest {

    private static final UUID SESSION = UUID.fromString("3f1c2a9e-7b1d-4c55-9a51-2f0e8f3b6d10");
    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0 Safari/537.36";
    private static final String TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EventService eventService;

    // --- /view ---

    @Test
    void viewReturns202WithEmptyBody() throws Exception {
        mvc.perform(post("/api/events/view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.USER_AGENT, BROWSER)
                        .content("{\"articleId\":42,\"sessionId\":\"" + SESSION + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(eventService).recordView(new ViewEventRequest(42L, SESSION), BROWSER);
    }

    @Test
    void viewAcceptsTextPlainBodyFromSendBeacon() throws Exception {
        mvc.perform(post("/api/events/view")
                        .contentType(TEXT_PLAIN_UTF8)
                        .content("{\"articleId\":42,\"sessionId\":\"" + SESSION + "\"}"))
                .andExpect(status().isAccepted());

        verify(eventService).recordView(new ViewEventRequest(42L, SESSION), null);
    }

    @Test
    void viewWithoutSessionIdReturns400ProblemDetail() throws Exception {
        mvc.perform(post("/api/events/view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("sessionId must not be null"))
                .andExpect(jsonPath("$.instance").value("/api/events/view"));

        verifyNoInteractions(eventService);
    }

    @Test
    void viewWithNonPositiveArticleIdReturns400() throws Exception {
        mvc.perform(post("/api/events/view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":0,\"sessionId\":\"" + SESSION + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("articleId")));
    }

    @Test
    void viewWithMalformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/events/view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":42,"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Malformed JSON body"));

        verifyNoInteractions(eventService);
    }

    @Test
    void viewWithInvalidUuidReturns400() throws Exception {
        mvc.perform(post("/api/events/view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":42,\"sessionId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sessionIdOutsideRfc9562Returns400InsteadOfDatabaseError() throws Exception {
        // Accepté par UUID.fromString mais rejeté par le type UUID de MariaDB (version d, variante 0)
        mvc.perform(post("/api/events/view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":42,\"sessionId\":\"9d7a93cd-13bb-d9ac-5c42-f172c66e4f6a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("sessionId must be an RFC 9562 UUID (version 1-8)"));
        mvc.perform(post("/api/events/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":42,\"sessionId\":\"00000000-0000-0000-0000-000000000000\","
                                + "\"timeSpentSeconds\":10,\"scrollPercent\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void viewWithMalformedTextPlainBodyReturns400() throws Exception {
        mvc.perform(post("/api/events/view")
                        .contentType(TEXT_PLAIN_UTF8)
                        .content("hello"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed JSON body"));
    }

    @Test
    void viewWithUnsupportedContentTypeReturns415() throws Exception {
        mvc.perform(post("/api/events/view")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<view/>"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(eventService);
    }

    // --- /read ---

    @Test
    void readReturns202() throws Exception {
        mvc.perform(post("/api/events/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.USER_AGENT, BROWSER)
                        .content(readBody(185, 94)))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(eventService).recordRead(new ReadEventRequest(42L, SESSION, 185, 94), BROWSER);
    }

    @Test
    void readAcceptsTextPlainBodyFromSendBeacon() throws Exception {
        mvc.perform(post("/api/events/read")
                        .contentType(TEXT_PLAIN_UTF8)
                        .header(HttpHeaders.USER_AGENT, BROWSER)
                        .content(readBody(185, 94)))
                .andExpect(status().isAccepted());

        verify(eventService).recordRead(new ReadEventRequest(42L, SESSION, 185, 94), BROWSER);
    }

    @Test
    void readWithScrollPercentAbove100Returns400() throws Exception {
        mvc.perform(post("/api/events/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readBody(185, 101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("scrollPercent")))
                .andExpect(jsonPath("$.instance").value("/api/events/read"));

        verifyNoInteractions(eventService);
    }

    @Test
    void readWithTimeSpentAbove1800Returns400() throws Exception {
        mvc.perform(post("/api/events/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readBody(1801, 50)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("timeSpentSeconds")));

        verifyNoInteractions(eventService);
    }

    @Test
    void readValidationAlsoAppliesToTextPlainBodies() throws Exception {
        mvc.perform(post("/api/events/read")
                        .contentType(TEXT_PLAIN_UTF8)
                        .content(readBody(-1, 50)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(eventService);
    }

    @Test
    void readWithMissingFieldsListsThemAll() throws Exception {
        mvc.perform(post("/api/events/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":42,\"sessionId\":\"" + SESSION + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "scrollPercent must not be null, timeSpentSeconds must not be null"));

        verifyNoInteractions(eventService);
    }

    private static String readBody(int timeSpentSeconds, int scrollPercent) {
        return "{\"articleId\":42,\"sessionId\":\"" + SESSION + "\",\"timeSpentSeconds\":" + timeSpentSeconds
                + ",\"scrollPercent\":" + scrollPercent + "}";
    }
}
