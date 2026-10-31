package com.nox.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.nox.adapter.in.web.error.ProblemResponseWriter;
import com.nox.adapter.in.web.error.ProblemType;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.support.CapturedLogs;
import com.nox.support.TestTracers;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authorization.AuthorizationDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProblemAccessDeniedHandlerTest {

    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ProblemAccessDeniedHandler handler = new ProblemAccessDeniedHandler(new ProblemResponseWriter(
            new MetaFactory(TestTracers.withTraceId("4bf92f3577b34da6a3ce929d0e0e4736"), Clock.systemUTC()),
            jsonMapper));
    private final CapturedLogs logs = CapturedLogs.of(SecurityEvents.class);

    @AfterEach
    void detachLogs() {
        logs.close();
    }

    @Test
    void writesForbiddenProblemAndLogsMissingRole() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test/tester-only");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AuthorizationDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.get("type").asString()).isEqualTo("urn:problem:forbidden");
        assertThat(body.get("detail").asString()).isEqualTo(ProblemType.FORBIDDEN.detail());
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/test/tester-only");

        assertThat(logs.events()).hasSize(1);
        ILoggingEvent event = logs.events().getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).isEqualTo("access denied");
        assertThat(CapturedLogs.keyValue(event, "reason")).isEqualTo("missing_role");
        assertThat(logs.allText()).doesNotContain(TOKEN).doesNotContain("eyJ");
    }
}
