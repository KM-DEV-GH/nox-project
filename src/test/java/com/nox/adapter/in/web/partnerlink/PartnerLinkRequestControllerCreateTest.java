package com.nox.adapter.in.web.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nox.adapter.in.web.error.GlobalExceptionHandler;
import com.nox.adapter.in.web.error.ProblemResponseWriter;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.application.port.in.partnerlink.GetPartnerLinkRequestStatusUseCase;
import com.nox.application.port.in.partnerlink.SubmitPartnerLinkRequestUseCase;
import com.nox.domain.partner.Partner;
import com.nox.domain.partner.PartnerStatus;
import com.nox.integration.support.ContractAssertions;
import com.nox.support.TestTracers;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(controllers = PartnerLinkRequestController.class)
@ActiveProfiles("web-slice")
@AutoConfigureMockMvc(addFilters = false)
@Import({
    GlobalExceptionHandler.class,
    ProblemResponseWriter.class,
    MetaFactory.class,
    PartnerLinkRequestControllerCreateTest.Collaborators.class
})
class PartnerLinkRequestControllerCreateTest {

    private static final String PATH = "/api/v1/partner-link-requests";
    private static final Partner PARTNER = new Partner(
            UUID.randomUUID(),
            "demo-partner",
            "Demo partner",
            null,
            PartnerStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"),
            null);
    private static final String VALID_BODY = """
            {"externalId":"76490253-9de4-4988-b757-ad0f13a5c7fd","email":"user@example.com","licenseeId":"nox-gaming-eu"}
            """;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubmitPartnerLinkRequestUseCase submit;

    @MockitoBean
    private GetPartnerLinkRequestStatusUseCase requestStatus;

    @Test
    void acceptsTheRequestWithTheContractBody() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(submit.submit(any())).thenReturn(requestId);

        String body = mockMvc.perform(create(VALID_BODY).header("Idempotency-Key", "key-1"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.meta.instance").value(PATH))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ContractAssertions.assertMatchesSchema(jsonMapper.readTree(body), "LinkRequestAcceptedResponse");
        ArgumentCaptor<SubmitPartnerLinkRequestUseCase.Command> command =
                ArgumentCaptor.forClass(SubmitPartnerLinkRequestUseCase.Command.class);
        verify(submit).submit(command.capture());
        assertThat(command.getValue())
                .isEqualTo(new SubmitPartnerLinkRequestUseCase.Command(
                        PARTNER.id(),
                        "key-1",
                        "76490253-9de4-4988-b757-ad0f13a5c7fd",
                        "user@example.com",
                        "nox-gaming-eu"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"email\":\"user@example.com\",\"licenseeId\":\"l\"}",
                "{\"externalId\":\"\",\"email\":\"user@example.com\",\"licenseeId\":\"l\"}",
                "{\"externalId\":\"e\",\"licenseeId\":\"l\"}",
                "{\"externalId\":\"e\",\"email\":\"not-an-email\",\"licenseeId\":\"l\"}",
                "{\"externalId\":\"e\",\"email\":\"user@example.com\"}",
                "{\"externalId\":\"e\",\"email\":\"user@example.com\",\"licenseeId\":\" \"}",
                "{\"externalId\":\"e\",\"email\":\"user@example.com\",\"licenseeId\":\"l\",\"extra\":1}",
                "not json"
            })
    void rejectsAnInvalidBody(String body) throws Exception {
        expectBadRequest(create(body).header("Idempotency-Key", "key-1"));
    }

    @Test
    void rejectsOverlongFields() throws Exception {
        String body = "{\"externalId\":\"%s\",\"email\":\"user@example.com\",\"licenseeId\":\"%s\"}"
                .formatted("e".repeat(129), "l".repeat(65));

        expectBadRequest(create(body).header("Idempotency-Key", "key-1"));
    }

    @Test
    void rejectsAMissingIdempotencyKey() throws Exception {
        expectBadRequest(create(VALID_BODY));
    }

    @Test
    void rejectsAnOverlongIdempotencyKey() throws Exception {
        expectBadRequest(create(VALID_BODY).header("Idempotency-Key", "k".repeat(129)));
    }

    private void expectBadRequest(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:bad_request"))
                .andExpect(jsonPath("$.instance").value(PATH));
        verifyNoInteractions(submit);
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post(PATH)
                .requestAttr(PartnerCallerResolver.PARTNER_ATTRIBUTE, PARTNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        Tracer tracer() {
            return TestTracers.withTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
