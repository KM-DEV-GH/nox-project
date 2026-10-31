package com.nox.adapter.in.web.partnerlink;

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
import com.nox.application.port.in.partnerlink.ApprovePartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.GetMyPartnerLinkHistoryUseCase;
import com.nox.application.port.in.partnerlink.GetMyPartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.LinkDecision;
import com.nox.application.port.in.partnerlink.ListMyPartnerLinksUseCase;
import com.nox.application.port.in.partnerlink.RejectPartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.RevokePartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.UserPartnerLink;
import com.nox.domain.partnerlink.LinkDecisionOutcome;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.infrastructure.config.WebMvcConfig;
import com.nox.integration.support.ContractAssertions;
import com.nox.support.TestTracers;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(controllers = PartnerLinkController.class)
@ActiveProfiles("web-slice")
@AutoConfigureMockMvc(addFilters = false)
@Import({
    GlobalExceptionHandler.class,
    ProblemResponseWriter.class,
    MetaFactory.class,
    WebMvcConfig.class,
    PartnerLinkControllerDecisionTest.Collaborators.class
})
class PartnerLinkControllerDecisionTest {

    static final String USER_ID = UUID.randomUUID().toString();

    private static final UUID LINK_ID = UUID.randomUUID();

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApprovePartnerLinkUseCase approve;

    @MockitoBean
    private RejectPartnerLinkUseCase reject;

    @MockitoBean
    private RevokePartnerLinkUseCase revoke;

    @MockitoBean
    private ListMyPartnerLinksUseCase list;

    @MockitoBean
    private GetMyPartnerLinkHistoryUseCase history;

    @MockitoBean
    private GetMyPartnerLinkUseCase get;

    @Test
    void approvalAnswersWithTheConnectedLink() throws Exception {
        when(approve.approve(LINK_ID, USER_ID))
                .thenReturn(decision(LinkDecisionOutcome.APPROVED, PartnerLinkStatus.CONNECTED));

        String body = perform("approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkId").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("CONNECTED"))
                .andExpect(jsonPath("$.data.partnerName").value("Demo partner"))
                .andExpect(jsonPath("$.meta.instance").value(path("approve")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ContractAssertions.assertMatchesSchema(jsonMapper.readTree(body), "UserLinkResponse");
        verify(approve).approve(LINK_ID, USER_ID);
    }

    @Test
    void rejectionAnswersWithTheRejectedLink() throws Exception {
        when(reject.reject(LINK_ID, USER_ID))
                .thenReturn(decision(LinkDecisionOutcome.REJECTED, PartnerLinkStatus.REJECTED));

        String body = perform("reject")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ContractAssertions.assertMatchesSchema(jsonMapper.readTree(body), "UserLinkResponse");
    }

    @Test
    void aLateDecisionIsLinkExpired() throws Exception {
        when(approve.approve(any(), any()))
                .thenReturn(decision(LinkDecisionOutcome.EXPIRED, PartnerLinkStatus.EXPIRED));
        when(reject.reject(any(), any())).thenReturn(decision(LinkDecisionOutcome.EXPIRED, PartnerLinkStatus.EXPIRED));

        expectProblem(perform("approve"), 409, "link_expired", path("approve"));
        expectProblem(perform("reject"), 409, "link_expired", path("reject"));
    }

    @Test
    void aDecisionOnADecidedLinkIsNotActionable() throws Exception {
        when(approve.approve(any(), any()))
                .thenReturn(decision(LinkDecisionOutcome.NOT_ACTIONABLE, PartnerLinkStatus.REJECTED));
        when(reject.reject(any(), any()))
                .thenReturn(decision(LinkDecisionOutcome.NOT_ACTIONABLE, PartnerLinkStatus.CONNECTED));

        expectProblem(perform("approve"), 409, "link_not_actionable", path("approve"));
        expectProblem(perform("reject"), 409, "link_not_actionable", path("reject"));
    }

    @Test
    void anUnknownOrForeignLinkIsNotFound() throws Exception {
        when(approve.approve(any(), any())).thenReturn(Optional.empty());
        when(reject.reject(any(), any())).thenReturn(Optional.empty());

        expectProblem(perform("approve"), 404, "not_found", path("approve"));
        expectProblem(perform("reject"), 404, "not_found", path("reject"));
    }

    @Test
    void aMalformedLinkIdIsABadRequest() throws Exception {
        expectProblem(
                mockMvc.perform(asUser(post("/api/v1/user-partner-links/not-a-uuid/approve"))),
                400,
                "bad_request",
                "/api/v1/user-partner-links/not-a-uuid/approve");
    }

    @Test
    void aMissingOrMalformedUserIdIsABadRequest() throws Exception {
        expectProblem(mockMvc.perform(post(path("approve"))), 400, "bad_request", path("approve"));
        expectProblem(
                mockMvc.perform(post(path("approve")).header(ActingUserIdArgumentResolver.USER_ID_HEADER, "alice")),
                400,
                "bad_request",
                path("approve"));
        verifyNoInteractions(approve);
    }

    private ResultActions perform(String action) throws Exception {
        return mockMvc.perform(asUser(post(path(action))));
    }

    /** The internal BFF acting for {@link #USER_ID}; security filters are off in this slice. */
    static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
        return request.header(ActingUserIdArgumentResolver.USER_ID_HEADER, USER_ID);
    }

    private static String path(String action) {
        return "/api/v1/user-partner-links/" + LINK_ID + "/" + action;
    }

    static void expectProblem(ResultActions result, int status, String title, String instance) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:" + title))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.instance").value(instance));
    }

    private static Optional<LinkDecision> decision(LinkDecisionOutcome outcome, PartnerLinkStatus status) {
        return Optional.of(new LinkDecision(outcome, link(LINK_ID, status)));
    }

    static UserPartnerLink link(UUID linkId, PartnerLinkStatus status) {
        return new UserPartnerLink(
                linkId,
                "Demo partner",
                null,
                status,
                Instant.parse("2026-10-29T09:00:00Z"),
                Instant.parse("2026-10-26T09:00:00Z"));
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
