package com.nox.adapter.in.web.partnerlink;

import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.USER_ID;
import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.asUser;
import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.expectProblem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nox.adapter.in.web.error.GlobalExceptionHandler;
import com.nox.adapter.in.web.error.ProblemResponseWriter;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.application.port.in.partnerlink.ApprovePartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.GetMyPartnerLinkHistoryUseCase;
import com.nox.application.port.in.partnerlink.GetMyPartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.ListMyPartnerLinksUseCase;
import com.nox.application.port.in.partnerlink.RejectPartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.RevokePartnerLinkUseCase;
import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import com.nox.infrastructure.config.WebMvcConfig;
import com.nox.integration.support.ContractAssertions;
import com.nox.support.TestTracers;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
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
    PartnerLinkControllerHistoryTest.Collaborators.class
})
class PartnerLinkControllerHistoryTest {

    private static final UUID LINK_ID = UUID.randomUUID();
    private static final String PATH = "/api/v1/user-partner-links/" + LINK_ID + "/history";
    private static final Instant CREATED = Instant.parse("2026-10-26T09:00:00Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetMyPartnerLinkHistoryUseCase history;

    @MockitoBean
    private RevokePartnerLinkUseCase revoke;

    @MockitoBean
    private ApprovePartnerLinkUseCase approve;

    @MockitoBean
    private RejectPartnerLinkUseCase reject;

    @MockitoBean
    private ListMyPartnerLinksUseCase list;

    @MockitoBean
    private GetMyPartnerLinkUseCase get;

    @Test
    void historyMatchesTheContractInOrder() throws Exception {
        when(history.history(LINK_ID, USER_ID))
                .thenReturn(Optional.of(List.of(
                        entry(null, PartnerLinkStatus.PENDING, ActorType.PARTNER, TransitionReason.LINK_CREATED, 0),
                        entry(
                                PartnerLinkStatus.PENDING,
                                PartnerLinkStatus.CONNECTED,
                                ActorType.USER,
                                TransitionReason.USER_APPROVED,
                                60))));

        String body = mockMvc.perform(asUser(get(PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fromStatus").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$.data[0].actorType").value("PARTNER"))
                .andExpect(jsonPath("$.data[0].reason").value("link_created"))
                .andExpect(jsonPath("$.data[1].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$.data[1].reason").value("user_approved"))
                .andExpect(jsonPath("$.meta.instance").value(PATH))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ContractAssertions.assertMatchesSchema(jsonMapper.readTree(body), "PartnerLinkHistoryResponse");
    }

    @Test
    void anotherUsersLinkIsNotFound() throws Exception {
        when(history.history(any(), any())).thenReturn(Optional.empty());

        expectProblem(mockMvc.perform(asUser(get(PATH))), 404, "not_found", PATH);
    }

    private static PartnerLinkHistoryEntry entry(
            PartnerLinkStatus from,
            PartnerLinkStatus to,
            ActorType actor,
            TransitionReason reason,
            long secondsAfterCreation) {
        return new PartnerLinkHistoryEntry(
                UUID.randomUUID(),
                LINK_ID,
                from,
                to,
                actor,
                "actor-1",
                CREATED.plusSeconds(secondsAfterCreation),
                reason);
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
