package com.nox.adapter.in.web.partnerlink;

import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.USER_ID;
import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.asUser;
import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.expectProblem;
import static com.nox.adapter.in.web.partnerlink.PartnerLinkControllerDecisionTest.link;
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
import com.nox.application.port.in.partnerlink.GetPartnerLinkForPartnerUseCase;
import com.nox.application.port.in.partnerlink.GetPartnerLinkRequestStatusUseCase;
import com.nox.application.port.in.partnerlink.ListMyPartnerLinksUseCase;
import com.nox.application.port.in.partnerlink.RejectPartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.RevokePartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.SubmitPartnerLinkRequestUseCase;
import com.nox.domain.partner.Partner;
import com.nox.domain.partner.PartnerStatus;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkRequestStatus;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.infrastructure.config.WebMvcConfig;
import com.nox.integration.support.ContractAssertions;
import com.nox.support.TestTracers;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(
        controllers = {PartnerLinkRequestController.class, PartnerLinkQueryController.class, PartnerLinkController.class
        })
@ActiveProfiles("web-slice")
@AutoConfigureMockMvc(addFilters = false)
@Import({
    GlobalExceptionHandler.class,
    ProblemResponseWriter.class,
    MetaFactory.class,
    WebMvcConfig.class,
    PartnerLinkQueryControllersTest.Collaborators.class
})
class PartnerLinkQueryControllersTest {

    private static final Partner PARTNER = new Partner(
            UUID.randomUUID(),
            "demo-partner",
            "Demo partner",
            null,
            PartnerStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"),
            null);
    private static final Instant CREATED = Instant.parse("2026-10-26T09:00:00Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubmitPartnerLinkRequestUseCase submit;

    @MockitoBean
    private GetPartnerLinkRequestStatusUseCase requestStatus;

    @MockitoBean
    private GetPartnerLinkForPartnerUseCase partnerLinks;

    @MockitoBean
    private ListMyPartnerLinksUseCase listMine;

    @MockitoBean
    private GetMyPartnerLinkHistoryUseCase history;

    @MockitoBean
    private GetMyPartnerLinkUseCase getMine;

    @MockitoBean
    private ApprovePartnerLinkUseCase approve;

    @MockitoBean
    private RejectPartnerLinkUseCase reject;

    @MockitoBean
    private RevokePartnerLinkUseCase revoke;

    @Test
    void requestStatusMatchesTheContract() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(requestStatus.status(PARTNER.id(), requestId))
                .thenReturn(Optional.of(PartnerLinkRequestStatus.PROCESSING));

        String body = mockMvc.perform(asPartner(get("/api/v1/partner-link-requests/" + requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ContractAssertions.assertMatchesSchema(jsonMapper.readTree(body), "LinkRequestStatusResponse");
    }

    @Test
    void anotherPartnersOrAnUnknownRequestIsNotFound() throws Exception {
        String path = "/api/v1/partner-link-requests/" + UUID.randomUUID();
        when(requestStatus.status(any(), any())).thenReturn(Optional.empty());

        expectProblem(mockMvc.perform(asPartner(get(path))), 404, "not_found", path);
    }

    @Test
    void partnerReadsAnEstablishedLinkByIdAndByExternalId() throws Exception {
        PartnerLink connected = established(PartnerLinkStatus.CONNECTED);
        when(partnerLinks.byId(PARTNER.id(), connected.id())).thenReturn(Optional.of(connected));
        when(partnerLinks.byExternalId(PARTNER.id(), "external-1")).thenReturn(Optional.of(connected));

        for (String path :
                List.of("/api/v1/partner-links/" + connected.id(), "/api/v1/partner-links/by-external/external-1")) {
            String body = mockMvc.perform(asPartner(get(path)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.linkId").value(connected.id().toString()))
                    .andExpect(jsonPath("$.data.externalId").value("external-1"))
                    .andExpect(jsonPath("$.data.status").value("CONNECTED"))
                    .andExpect(jsonPath("$.meta.instance").value(path))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            ContractAssertions.assertMatchesSchema(jsonMapper.readTree(body), "PartnerLinkResponse");
        }
    }

    @Test
    void partnerReadOfAnythingElseIsNotFound() throws Exception {
        when(partnerLinks.byId(any(), any())).thenReturn(Optional.empty());
        when(partnerLinks.byExternalId(any(), any())).thenReturn(Optional.empty());
        String byId = "/api/v1/partner-links/" + UUID.randomUUID();
        String byExternal = "/api/v1/partner-links/by-external/external-2";

        expectProblem(mockMvc.perform(asPartner(get(byId))), 404, "not_found", byId);
        expectProblem(mockMvc.perform(asPartner(get(byExternal))), 404, "not_found", byExternal);
    }

    @Test
    void userListsTheirLinks() throws Exception {
        UUID pending = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        when(listMine.list(USER_ID))
                .thenReturn(
                        List.of(link(pending, PartnerLinkStatus.PENDING), link(expired, PartnerLinkStatus.EXPIRED)));

        String body = mockMvc.perform(asUser(get("/api/v1/user-partner-links")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].linkId").value(pending.toString()))
                .andExpect(jsonPath("$.data[1].status").value("EXPIRED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ContractAssertions.assertMatchesSchema(jsonMapper.readTree(body), "UserLinkListResponse");
    }

    @Test
    void userReadsOneOfTheirLinks() throws Exception {
        UUID linkId = UUID.randomUUID();
        when(getMine.get(linkId, USER_ID)).thenReturn(Optional.of(link(linkId, PartnerLinkStatus.CONNECTED)));

        String body = mockMvc.perform(asUser(get("/api/v1/user-partner-links/" + linkId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONNECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ContractAssertions.assertMatchesSchema(jsonMapper.readTree(body), "UserLinkResponse");
    }

    @Test
    void anotherUsersLinkIsNotFound() throws Exception {
        String path = "/api/v1/user-partner-links/" + UUID.randomUUID();
        when(getMine.get(any(), any())).thenReturn(Optional.empty());

        expectProblem(mockMvc.perform(asUser(get(path))), 404, "not_found", path);
    }

    private static MockHttpServletRequestBuilder asPartner(MockHttpServletRequestBuilder request) {
        return request.requestAttr(PartnerCallerResolver.PARTNER_ATTRIBUTE, PARTNER);
    }

    private static PartnerLink established(PartnerLinkStatus status) {
        return PartnerLink.restore(
                UUID.randomUUID(),
                "user-1",
                PARTNER.id(),
                "external-1",
                "licensee-1",
                status,
                CREATED.plusSeconds(3600),
                1L,
                CREATED,
                CREATED);
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
