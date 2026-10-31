package com.nox.adapter.in.web.error;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.application.port.in.payment.PaymentException;
import com.nox.application.port.in.payment.PaymentFailure;
import com.nox.support.TestTracers;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.SampleController.class)
@ActiveProfiles("web-slice")
@AutoConfigureMockMvc(addFilters = false)
@Import({
    GlobalExceptionHandler.class,
    ProblemResponseWriter.class,
    MetaFactory.class,
    GlobalExceptionHandlerTest.Collaborators.class,
    GlobalExceptionHandlerTest.SampleController.class
})
class GlobalExceptionHandlerTest {

    private static final String INTERNAL_MESSAGE = "database password is hunter2";

    private final MockMvc mockMvc;

    GlobalExceptionHandlerTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void unknownPathIsNotFoundProblem() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:not_found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/does-not-exist"));
    }

    @Test
    void unsupportedMethodIsMethodNotAllowedProblemWithAllowHeader() throws Exception {
        mockMvc.perform(post("/sample"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", Matchers.containsString("GET")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:method_not_allowed"));
    }

    @Test
    void unsatisfiableAcceptIsNotAcceptableProblem() throws Exception {
        mockMvc.perform(get("/sample").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:not_acceptable"));
    }

    @Test
    void unexpectedExceptionIsInternalErrorWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/sample/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:internal_error"))
                .andExpect(jsonPath("$.detail").value(ProblemType.INTERNAL_ERROR.detail()))
                .andExpect(content().string(Matchers.not(Matchers.containsString(INTERNAL_MESSAGE))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("IllegalStateException"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("at com.nox"))));
    }

    /** Every payment failure has one documented problem type (005 contracts/openapi.yaml). */
    @ParameterizedTest
    @CsvSource({
        "LINK_NOT_FOUND, 404, not_found",
        "PAYMENT_NOT_FOUND, 404, not_found",
        "LINK_NOT_CONNECTED, 409, link_not_actionable",
        "LIMIT_EXCEEDED, 422, payment_limit_exceeded",
        "NOT_CONFIGURED, 422, payment_not_configured",
        "IDEMPOTENCY_CONFLICT, 409, idempotency_conflict",
        "EXTERNAL_ID_CONFLICT, 409, external_id_conflict",
        "IN_PROGRESS, 409, payment_in_progress",
        "NOT_ACTIONABLE, 409, payment_not_actionable",
        "REJECTED, 422, payment_rejected",
        "OTP_REJECTED, 422, payment_otp_rejected",
        "PROCESSOR_UNAVAILABLE, 503, payment_processor_unavailable",
        "UNRESOLVED, 503, payment_unresolved"
    })
    void paymentFailuresAreTheirDocumentedProblems(PaymentFailure failure, int status, String title) throws Exception {
        mockMvc.perform(get("/sample/payment/" + failure.name()))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:" + title))
                .andExpect(jsonPath("$.title").value(title));
    }

    @Test
    void accessDeniedIsLeftToSpringSecurity() {
        assertThatThrownBy(() -> mockMvc.perform(get("/sample/denied")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    @RestController
    static class SampleController {

        @GetMapping("/sample")
        Map<String, String> sample() {
            return Map.of("value", "ok");
        }

        @GetMapping("/sample/denied")
        String denied() {
            throw new AccessDeniedException("Access Denied");
        }

        @GetMapping("/sample/payment/{failure}")
        String payment(@PathVariable PaymentFailure failure) {
            throw new PaymentException(failure);
        }

        @GetMapping("/sample/boom")
        String boom() {
            throw new IllegalStateException(INTERNAL_MESSAGE);
        }
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
