package com.nox.adapter.in.web.partnerlink;

import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.USER_ID;
import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.asUser;
import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.expectProblem;
import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.link;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.nox.domain.partnerlink.LinkDecisionOutcome;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.infrastructure.config.WebMvcConfig;
import com.nox.integration.support.ContractAssertions;
import com.nox.support.TestTracers;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(controllers = PartnerLinkController.class)
@ActiveProfiles("web-slice")
@AutoConfigureMockMvc(addFilters = false)
@Import({
    GlobalExceptionHandler.class,
    ProblemResponseWriter.class,
    MetaFactory.class,
    WebMvcConfig.class,
    PartnerLinkControllerRevokeTest.Collaborators.class
})
class PartnerLinkControllerRevokeTest {

    private static final UUID LINK_ID = UUID.randomUUID();
    private static final String PATH = "/api/v1/user-partner-links/" + LINK_ID + "/revoke";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RevokePartnerLinkUseCase revoke;

    @MockitoBean
    private ApprovePartnerLinkUseCase approve;

    @MockitoBean
    private RejectPartnerLinkUseCase reject;

    @MockitoBean
    private ListMyPartnerLinksUseCase list;

    @MockitoBean
    private GetMyPartnerLinkHistoryUseCase history;

    @MockitoBean
    private GetMyPartnerLinkUseCase get;

    @Test
    void revocationAnswersWithTheRevokedLink() throws Exception {
        when(revoke.revoke(LINK_ID, USER_ID))
                .thenReturn(Optional.of(
                        new LinkDecision(LinkDecisionOutcome.REVOKED, link(LINK_ID, PartnerLinkStatus.REVOKED))));

        String body = mockMvc.perform(asUser(post(PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ContractAssertions.assertMatchesSchema(jsonMapper.readTree(body), "UserLinkResponse");
    }

    @Test
    void anythingButAConnectedLinkIsNotActionable() throws Exception {
        when(revoke.revoke(any(), any()))
                .thenReturn(Optional.of(new LinkDecision(
                        LinkDecisionOutcome.NOT_ACTIONABLE, link(LINK_ID, PartnerLinkStatus.PENDING))));

        expectProblem(mockMvc.perform(asUser(post(PATH))), 409, "link_not_actionable", PATH);
    }

    @Test
    void anotherUsersLinkIsNotFound() throws Exception {
        when(revoke.revoke(any(), any())).thenReturn(Optional.empty());

        expectProblem(mockMvc.perform(asUser(post(PATH))), 404, "not_found", PATH);
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
